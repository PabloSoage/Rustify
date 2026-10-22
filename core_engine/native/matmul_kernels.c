// Matrix-product kernels for the audio lab's key and mel analysis (4.7.1): C = A·B, where A is a
// filterbank (one row per chroma class or mel band, one column per FFT bin) and B holds a batch of
// spectra (one column per frame). This is the shape the newer ARM extensions were made for, unlike
// the FFT: every input value is reused across a whole row or column of the result.
//
// Layouts (the Rust side packs them, `tonal.rs`):
//  - fp32 kernels: `at` is Aᵀ (k×m, column p of A contiguous), `b` is k×n row-major.
//  - BFMMLA (armv8.6 BF16): A in 2-row × 4-deep bf16 panels, B in 2-column × 4-deep panels.
//  - BFMOPA (armv9.2 SME): A and B as bf16 pairs along k: `ap[kp·2m + 2i + e] = A[i][2kp + e]`.
// C is always m×n row-major in fp32.
//
//  - rf_mm_neon:      armv8.0 NEON FMLA by lane, a 4×16 block of C in registers.
//  - rf_mm_bf16:      armv8.6 BFMMLA: each instruction is a 2×4 by 4×2 product, 16 multiply-adds.
//  - rf_mm_sme:       armv9.2 SME FMOPA: each instruction adds an outer product to a ZA tile —
//                     with 512-bit streaming vectors, 16×16 = 256 multiply-adds.
//  - rf_mm_sme_bf16:  armv9.2 SME BFMOPA: the same with bf16 pairs, 512 multiply-adds each.
//
// Shapes: m % 4 == 0, n % 16 == 0, k % 4 == 0 (the SME kernels take any shape, with predicates).

#include <stddef.h>
#include <stdint.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#include <arm_sme.h>

#define RF_NEON_ROW(r)                                                     \
    acc##r##0 = vfmaq_laneq_f32(acc##r##0, b0, a, r);                      \
    acc##r##1 = vfmaq_laneq_f32(acc##r##1, b1, a, r);                      \
    acc##r##2 = vfmaq_laneq_f32(acc##r##2, b2, a, r);                      \
    acc##r##3 = vfmaq_laneq_f32(acc##r##3, b3, a, r);

#define RF_NEON_STORE(r)                                                   \
    vst1q_f32(c + (i + r) * n + j, acc##r##0);                             \
    vst1q_f32(c + (i + r) * n + j + 4, acc##r##1);                         \
    vst1q_f32(c + (i + r) * n + j + 8, acc##r##2);                         \
    vst1q_f32(c + (i + r) * n + j + 12, acc##r##3);

void rf_mm_neon(const float *at, const float *b, float *c, size_t m, size_t n, size_t k) {
    for (size_t i = 0; i < m; i += 4) {
        for (size_t j = 0; j < n; j += 16) {
            float32x4_t z = vdupq_n_f32(0.0f);
            float32x4_t acc00 = z, acc01 = z, acc02 = z, acc03 = z, acc10 = z, acc11 = z, acc12 = z, acc13 = z;
            float32x4_t acc20 = z, acc21 = z, acc22 = z, acc23 = z, acc30 = z, acc31 = z, acc32 = z, acc33 = z;
            for (size_t p = 0; p < k; p++) {
                float32x4_t a = vld1q_f32(at + p * m + i);
                const float *bp = b + p * n + j;
                float32x4_t b0 = vld1q_f32(bp), b1 = vld1q_f32(bp + 4), b2 = vld1q_f32(bp + 8), b3 = vld1q_f32(bp + 12);
                RF_NEON_ROW(0) RF_NEON_ROW(1) RF_NEON_ROW(2) RF_NEON_ROW(3)
            }
            RF_NEON_STORE(0) RF_NEON_STORE(1) RF_NEON_STORE(2) RF_NEON_STORE(3)
        }
    }
}

// One accumulator holds C rows (2x, 2x+1) × columns (2y, 2y+1) as [r0c0, r0c1, r1c0, r1c1].
#define RF_BF16_COL(y)                                                                             \
    acc0##y = vbfmmlaq_f32(acc0##y, a0, vreinterpretq_bf16_u16(vld1q_u16(bq + (y) * panel)));      \
    acc1##y = vbfmmlaq_f32(acc1##y, a1, vreinterpretq_bf16_u16(vld1q_u16(bq + (y) * panel)));

#define RF_BF16_STORE(x, y)                                                                        \
    vst1_f32(c + (i + 2 * (x)) * n + j + 2 * (y), vget_low_f32(acc##x##y));                         \
    vst1_f32(c + (i + 2 * (x) + 1) * n + j + 2 * (y), vget_high_f32(acc##x##y));

// `ap`: for each row pair, k/4 panels of 8 bf16 (row 2i's four k, then row 2i+1's). `bp` the same
// per column pair. Four rows × sixteen columns per pass: 16 accumulators, 2 A and 8 B panels.
__attribute__((target("+bf16")))
void rf_mm_bf16(const uint16_t *ap, const uint16_t *bp, float *c, size_t m, size_t n, size_t k) {
    size_t panel = (k / 4) * 8; // u16 per row pair (or column pair)
    for (size_t i = 0; i < m; i += 4) {
        for (size_t j = 0; j < n; j += 16) {
            float32x4_t z = vdupq_n_f32(0.0f);
            float32x4_t acc00 = z, acc01 = z, acc02 = z, acc03 = z, acc04 = z, acc05 = z, acc06 = z, acc07 = z;
            float32x4_t acc10 = z, acc11 = z, acc12 = z, acc13 = z, acc14 = z, acc15 = z, acc16 = z, acc17 = z;
            const uint16_t *a_pair = ap + (i / 2) * panel;
            const uint16_t *b_pair = bp + (j / 2) * panel;
            for (size_t q = 0; q < k / 4; q++) {
                bfloat16x8_t a0 = vreinterpretq_bf16_u16(vld1q_u16(a_pair + q * 8));
                bfloat16x8_t a1 = vreinterpretq_bf16_u16(vld1q_u16(a_pair + panel + q * 8));
                const uint16_t *bq = b_pair + q * 8;
                RF_BF16_COL(0) RF_BF16_COL(1) RF_BF16_COL(2) RF_BF16_COL(3)
                RF_BF16_COL(4) RF_BF16_COL(5) RF_BF16_COL(6) RF_BF16_COL(7)
            }
            RF_BF16_STORE(0, 0) RF_BF16_STORE(0, 1) RF_BF16_STORE(0, 2) RF_BF16_STORE(0, 3)
            RF_BF16_STORE(0, 4) RF_BF16_STORE(0, 5) RF_BF16_STORE(0, 6) RF_BF16_STORE(0, 7)
            RF_BF16_STORE(1, 0) RF_BF16_STORE(1, 1) RF_BF16_STORE(1, 2) RF_BF16_STORE(1, 3)
            RF_BF16_STORE(1, 4) RF_BF16_STORE(1, 5) RF_BF16_STORE(1, 6) RF_BF16_STORE(1, 7)
        }
    }
}

// Writes the four fp32 ZA tiles (stacked: rows i0.., i0+svl.., …) to C, rows past m skipped.
#define RF_ZA_STORE(t)                                                                             \
    if (i0 + (t) * svl + r < m) svst1_hor_za32(t, r, pn, c + (i0 + (t) * svl + r) * n + j0);

// SME FMOPA. The four 32-bit ZA tiles cover 4·SVL rows of C and one SVL-wide strip of columns; per
// k, one B vector and four A vectors make four outer products.
__attribute__((target("+sme,+sve2")))
__arm_new("za") __arm_locally_streaming
void rf_mm_sme(const float *at, const float *b, float *c, size_t m, size_t n, size_t k) {
    uint64_t svl = svcntw();
    for (size_t i0 = 0; i0 < m; i0 += 4 * svl) {
        svbool_t pm0 = svwhilelt_b32_u64(i0, m), pm1 = svwhilelt_b32_u64(i0 + svl, m);
        svbool_t pm2 = svwhilelt_b32_u64(i0 + 2 * svl, m), pm3 = svwhilelt_b32_u64(i0 + 3 * svl, m);
        for (size_t j0 = 0; j0 < n; j0 += svl) {
            svbool_t pn = svwhilelt_b32_u64(j0, n);
            svzero_za();
            for (size_t p = 0; p < k; p++) {
                svfloat32_t bv = svld1_f32(pn, b + p * n + j0);
                const float *a = at + p * m + i0;
                svmopa_za32_f32_m(0, pm0, pn, svld1_f32(pm0, a), bv);
                svmopa_za32_f32_m(1, pm1, pn, svld1_f32(pm1, a + svl), bv);
                svmopa_za32_f32_m(2, pm2, pn, svld1_f32(pm2, a + 2 * svl), bv);
                svmopa_za32_f32_m(3, pm3, pn, svld1_f32(pm3, a + 3 * svl), bv);
            }
            for (uint32_t r = 0; r < svl; r++) {
                RF_ZA_STORE(0) RF_ZA_STORE(1) RF_ZA_STORE(2) RF_ZA_STORE(3)
            }
        }
    }
}

// SME BFMOPA: bf16 pairs along k, widened and summed into the same fp32 tiles — half the k steps.
__attribute__((target("+sme,+sve2,+bf16")))
__arm_new("za") __arm_locally_streaming
void rf_mm_sme_bf16(const uint16_t *ap, const uint16_t *bp, float *c, size_t m, size_t n, size_t k) {
    uint64_t svl = svcntw();
    for (size_t i0 = 0; i0 < m; i0 += 4 * svl) {
        svbool_t pm0 = svwhilelt_b16_u64(2 * i0, 2 * m), pm1 = svwhilelt_b16_u64(2 * (i0 + svl), 2 * m);
        svbool_t pm2 = svwhilelt_b16_u64(2 * (i0 + 2 * svl), 2 * m), pm3 = svwhilelt_b16_u64(2 * (i0 + 3 * svl), 2 * m);
        for (size_t j0 = 0; j0 < n; j0 += svl) {
            svbool_t pn16 = svwhilelt_b16_u64(2 * j0, 2 * n);
            svbool_t pn = svwhilelt_b32_u64(j0, n);
            svzero_za();
            for (size_t q = 0; q < k / 2; q++) {
                svbfloat16_t bv = svld1_bf16(pn16, (const bfloat16_t *)(bp + q * 2 * n + 2 * j0));
                const bfloat16_t *a = (const bfloat16_t *)(ap + q * 2 * m + 2 * i0);
                svmopa_za32_bf16_m(0, pm0, pn16, svld1_bf16(pm0, a), bv);
                svmopa_za32_bf16_m(1, pm1, pn16, svld1_bf16(pm1, a + 2 * svl), bv);
                svmopa_za32_bf16_m(2, pm2, pn16, svld1_bf16(pm2, a + 4 * svl), bv);
                svmopa_za32_bf16_m(3, pm3, pn16, svld1_bf16(pm3, a + 6 * svl), bv);
            }
            for (uint32_t r = 0; r < svl; r++) {
                RF_ZA_STORE(0) RF_ZA_STORE(1) RF_ZA_STORE(2) RF_ZA_STORE(3)
            }
        }
    }
}

#endif
