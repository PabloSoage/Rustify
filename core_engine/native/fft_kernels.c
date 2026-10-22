// Hand-written FFT kernels for the audio lab (4.7.1), one per ARM generation, all computing the
// same radix-2 butterflies over split real/imaginary arrays already in bit-reversed order, with the
// twiddles of each stage stored contiguously (stage with half-size h at offset h - 1).
//
// Each function carries its own `target` attribute, so this file is compiled for armv8.0 and only
// these functions use the newer instructions. The Rust side calls one only after the CPU has said
// it has the extension (`dsp::Kernel::available`): a phone without SVE2 never runs an SVE2 kernel.
//
//  - rf_fft_neon:  armv8.0 NEON, four butterflies per instruction (128-bit vectors).
//  - rf_fft_sve2:  armv9 SVE2, vector-length agnostic: as many butterflies per instruction as the
//                  core's SVE vector holds, with predicated tails instead of scalar remainders.
//  - rf_fft_ssve:  armv9.2 SME. The same SVE code run in streaming mode, where vectors are as wide
//                  as the SME unit's (SVL), which on some cores is wider than the SVE vector.

#include <stddef.h>
#include <stdint.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#include <arm_sve.h>
#include <arm_sme.h>

// Scalar butterflies for the first stages, where a stage is narrower than a vector.
static inline void scalar_stage(float *re, float *im, size_t n, size_t half, const float *wr, const float *wi) {
    size_t size = half << 1;
    for (size_t start = 0; start < n; start += size) {
        for (size_t j = 0; j < half; j++) {
            float *ar = re + start + j, *ai = im + start + j;
            float *br = ar + half, *bi = ai + half;
            float tr = *br * wr[j] - *bi * wi[j];
            float ti = *br * wi[j] + *bi * wr[j];
            *br = *ar - tr; *bi = *ai - ti;
            *ar += tr; *ai += ti;
        }
    }
}

// NEON stages while the half-size is below `until`; the whole FFT when `until` is n.
static void neon_stages(float *re, float *im, size_t n, const float *twr, const float *twi, size_t until) {
    for (size_t half = 1; half < n && half < until; half <<= 1) {
        const float *wr = twr + half - 1, *wi = twi + half - 1;
        if (half < 4) { scalar_stage(re, im, n, half, wr, wi); continue; }
        size_t size = half << 1;
        for (size_t start = 0; start < n; start += size) {
            for (size_t j = 0; j < half; j += 4) {
                float *ar = re + start + j, *ai = im + start + j;
                float *br = ar + half, *bi = ai + half;
                float32x4_t w_r = vld1q_f32(wr + j), w_i = vld1q_f32(wi + j);
                float32x4_t xr = vld1q_f32(br), xi = vld1q_f32(bi);
                float32x4_t tr = vfmsq_f32(vmulq_f32(xr, w_r), xi, w_i);
                float32x4_t ti = vfmaq_f32(vmulq_f32(xr, w_i), xi, w_r);
                float32x4_t yr = vld1q_f32(ar), yi = vld1q_f32(ai);
                vst1q_f32(br, vsubq_f32(yr, tr));
                vst1q_f32(bi, vsubq_f32(yi, ti));
                vst1q_f32(ar, vaddq_f32(yr, tr));
                vst1q_f32(ai, vaddq_f32(yi, ti));
            }
        }
    }
}

// The SVE body, shared by the normal and the streaming kernel. `ATTR` is what makes the function
// legal in its mode: plain SVE2, or streaming (SME) where svcntw() is the SME vector length.
#define RF_SVE_FFT_BODY                                                                          \
    size_t vl = svcntw();                                                                        \
    for (size_t half = first_half; half < n; half <<= 1) {                                                \
        const float *wr = twr + half - 1, *wi = twi + half - 1;                                  \
        size_t size = half << 1;                                                                 \
        if (half < vl) {                                                                         \
            for (size_t start = 0; start < n; start += size) {                                   \
                for (size_t j = 0; j < half; j++) {                                              \
                    float *ar = re + start + j, *ai = im + start + j;                            \
                    float *br = ar + half, *bi = ai + half;                                      \
                    float tr = *br * wr[j] - *bi * wi[j];                                        \
                    float ti = *br * wi[j] + *bi * wr[j];                                        \
                    *br = *ar - tr; *bi = *ai - ti; *ar += tr; *ai += ti;                        \
                }                                                                                \
            }                                                                                    \
            continue;                                                                            \
        }                                                                                        \
        for (size_t start = 0; start < n; start += size) {                                       \
            for (size_t j = 0; j < half; j += vl) {                                              \
                svbool_t pg = svwhilelt_b32_u64(j, half);                                        \
                float *ar = re + start + j, *ai = im + start + j;                                \
                float *br = ar + half, *bi = ai + half;                                          \
                svfloat32_t w_r = svld1_f32(pg, wr + j), w_i = svld1_f32(pg, wi + j);            \
                svfloat32_t xr = svld1_f32(pg, br), xi = svld1_f32(pg, bi);                      \
                svfloat32_t tr = svmls_f32_x(pg, svmul_f32_x(pg, xr, w_r), xi, w_i);             \
                svfloat32_t ti = svmla_f32_x(pg, svmul_f32_x(pg, xr, w_i), xi, w_r);             \
                svfloat32_t yr = svld1_f32(pg, ar), yi = svld1_f32(pg, ai);                      \
                svst1_f32(pg, br, svsub_f32_x(pg, yr, tr));                                      \
                svst1_f32(pg, bi, svsub_f32_x(pg, yi, ti));                                      \
                svst1_f32(pg, ar, svadd_f32_x(pg, yr, tr));                                      \
                svst1_f32(pg, ai, svadd_f32_x(pg, yi, ti));                                      \
            }                                                                                    \
        }                                                                                        \
    }

void rf_fft_neon(float *re, float *im, size_t n, const float *twr, const float *twi) {
    neon_stages(re, im, n, twr, twi, n);
}

__attribute__((target("+sve2")))
void rf_fft_sve2(float *re, float *im, size_t n, const float *twr, const float *twi) {
    size_t first_half = 1;
    RF_SVE_FFT_BODY
}

// The stages from `first_half` on, in streaming mode.
__attribute__((target("+sme,+sve2")))
__arm_locally_streaming
static void ssve_stages(float *re, float *im, size_t n, const float *twr, const float *twi, size_t first_half) {
    RF_SVE_FFT_BODY
}

__attribute__((target("+sme,+sve2")))
__arm_locally_streaming
static size_t sme_svl_floats(void) { return svcntw(); }

// SME: the narrow first stages stay in normal mode with NEON — scalar code in streaming mode is
// slow on these cores — and only the stages at least one streaming vector wide enter streaming.
void rf_fft_ssve(float *re, float *im, size_t n, const float *twr, const float *twi) {
    size_t svl = sme_svl_floats();
    neon_stages(re, im, n, twr, twi, svl);
    if (svl < n) ssve_stages(re, im, n, twr, twi, svl);
}

// Vector lengths in bytes, for the lab: SVE's, and the SME unit's (streaming) one.
__attribute__((target("+sve")))
size_t rf_sve_vl_bytes(void) { return svcntb(); }

__attribute__((target("+sme,+sve2")))
__arm_locally_streaming
size_t rf_sme_svl_bytes(void) { return svcntb(); }

#endif
