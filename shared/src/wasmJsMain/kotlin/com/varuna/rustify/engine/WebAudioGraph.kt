// shared/src/wasmJsMain/kotlin/com/varuna/rustify/engine/WebAudioGraph.kt
package com.varuna.rustify.engine

/*
 * The effects the browser can apply, over Web Audio: what the engine does in `engine/effects.rs`,
 * for the part a page has nodes for. Each <audio> goes in through its own gain (its fades), into one
 * chain: pre-amp, the equaliser's and AutoEq's filters, the stereo tools, the volume. The chain is
 * rebuilt whole when the effects change. Every source is the page's own origin (the server's /api
 * routes), so the browser lets the page hear it: a cross-origin element would come out silent.
 *
 * Not here, and so not offered on the web: loudness normalisation and ReplayGain (they need the
 * track measured or its tags read first), skipping silence, and pitch apart from speed.
 */

private fun newContext(): JsAny = js("new (window.AudioContext || window.webkitAudioContext)()")

private fun resume(ctx: JsAny): Unit = js("{ if (ctx.state === 'suspended') ctx.resume(); }")

private fun sourceOf(ctx: JsAny, audio: JsAny): JsAny = js("ctx.createMediaElementSource(audio)")

private fun gainNode(ctx: JsAny, value: Double): JsAny = js("(function(){ var g = ctx.createGain(); g.gain.value = value; return g; })()")

private fun biquad(ctx: JsAny, type: String, freq: Double, gainDb: Double, q: Double): JsAny =
    js("(function(){ var f = ctx.createBiquadFilter(); f.type = type; f.frequency.value = freq; f.gain.value = gainDb; f.Q.value = q; return f; })()")

private fun panner(ctx: JsAny, pan: Double): JsAny = js("(function(){ var p = ctx.createStereoPanner(); p.pan.value = pan; return p; })()")

/** Both channels as one: a node that takes one channel down-mixes, the next one up-mixes it to both sides. */
private fun monoNode(ctx: JsAny): JsAny =
    js("(function(){ var g = ctx.createGain(); g.channelCount = 1; g.channelCountMode = 'explicit'; g.channelInterpretation = 'speakers'; return g; })()")

/** Left minus right on both sides: what is panned dead centre (most often the voice) cancels out. */
private fun voiceReduction(ctx: JsAny): JsAny = js("""(function(){
    var split = ctx.createChannelSplitter(2), left = ctx.createGain(), right = ctx.createGain();
    var sum = ctx.createGain();
    right.gain.value = -1;
    sum.channelCount = 1; sum.channelCountMode = 'explicit'; sum.channelInterpretation = 'discrete';
    split.connect(left, 0); split.connect(right, 1); left.connect(sum); right.connect(sum);
    return { input: split, output: sum };
})()""")

/** Each ear hears a little of the other side, low-passed and softer: headphones as speakers in a room. */
private fun crossfeed(ctx: JsAny): JsAny = js("""(function(){
    var split = ctx.createChannelSplitter(2), merge = ctx.createChannelMerger(2);
    function lowpass() { var f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = 700; return f; }
    function gain(v) { var g = ctx.createGain(); g.gain.value = v; return g; }
    var lDirect = gain(0.85), rDirect = gain(0.85), lToR = gain(0.3), rToL = gain(0.3);
    var lpL = lowpass(), lpR = lowpass();
    split.connect(lDirect, 0); lDirect.connect(merge, 0, 0);
    split.connect(rDirect, 1); rDirect.connect(merge, 0, 1);
    split.connect(lpL, 0); lpL.connect(lToR); lToR.connect(merge, 0, 1);
    split.connect(lpR, 1); lpR.connect(rToL); rToL.connect(merge, 0, 0);
    return { input: split, output: merge };
})()""")

private fun inputOf(stage: JsAny): JsAny = js("stage.input")

private fun outputOf(stage: JsAny): JsAny = js("stage.output")

private fun connect(from: JsAny, to: JsAny): Unit = js("from.connect(to)")

private fun destination(ctx: JsAny): JsAny = js("ctx.destination")

private fun disconnect(node: JsAny): Unit = js("{ try { node.disconnect(); } catch (e) {} }")

private fun rampTo(ctx: JsAny, gain: JsAny, target: Double, seconds: Double): Unit = js("""{
    var t = ctx.currentTime, p = gain.gain;
    p.cancelScheduledValues(t); p.setValueAtTime(p.value, t);
    if (seconds > 0) p.linearRampToValueAtTime(target, t + seconds); else p.setValueAtTime(target, t);
}""")

private fun setGain(gain: JsAny, value: Double): Unit = js("{ gain.gain.cancelScheduledValues(0); gain.gain.value = value; }")

private fun later(ms: Int, action: () -> Unit): Unit = js("setTimeout(function() { action(); }, ms)")

/** One AutoEq filter, as `ParametricEQ.txt` writes it: `Filter 1: ON PK Fc 105 Hz Gain -2.2 dB Q 0.70`. */
internal data class PeqFilter(val type: String, val freqHz: Double, val gainDb: Double, val q: Double)

/** AutoEq's `ParametricEQ.txt`: its pre-amp and its filters that are on, in the browser's filter names. */
internal fun parsePeq(text: String): Pair<Double, List<PeqFilter>> {
    var preamp = 0.0
    val filters = mutableListOf<PeqFilter>()
    for (line in text.lines()) {
        val words = line.trim().split(Regex("\\s+"))
        if (words.firstOrNull().equals("Preamp:", ignoreCase = true)) {
            preamp = words.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            continue
        }
        if (!words.firstOrNull().orEmpty().startsWith("Filter", ignoreCase = true)) continue
        if (words.none { it.equals("ON", ignoreCase = true) }) continue
        fun after(key: String) = words.indexOfFirst { it.equals(key, ignoreCase = true) }.takeIf { it >= 0 }?.let { words.getOrNull(it + 1)?.toDoubleOrNull() }
        val type = when {
            "PK" in words -> "peaking"
            "LSC" in words || "LS" in words -> "lowshelf"
            "HSC" in words || "HS" in words -> "highshelf"
            else -> continue
        }
        val freq = after("Fc") ?: continue
        filters += PeqFilter(type, freq, after("Gain") ?: 0.0, after("Q") ?: 0.707)
    }
    return preamp to filters
}

/** The page's audio graph: [attach] each element as it becomes the one playing, [apply] the effects. */
internal class WebAudioGraph {
    private var ctx: JsAny? = null
    private var bus: JsAny? = null
    private val chain = mutableListOf<JsAny>()
    private var master: JsAny? = null
    private var effects = EffectsConfig()
    private var volume = 1.0
    private val attached = mutableListOf<Pair<JsAny, JsAny>>() // element → its own gain

    /** The context, made on first use: the browser lets one start only from a gesture (play is one). */
    private fun context(): JsAny = ctx ?: newContext().also { c ->
        ctx = c
        bus = gainNode(c, 1.0)
        build()
    }

    /** Routes [audio] through the effects; answers its own gain, for its fades. Once per element. */
    fun attach(audio: JsAny): JsAny {
        attached.firstOrNull { it.first === audio }?.let { return it.second }
        val c = context()
        val own = gainNode(c, 1.0)
        connect(sourceOf(c, audio), own)
        connect(own, bus!!)
        attached += audio to own
        // Elements no longer playing or preloaded are let go, or the list would hold every song.
        while (attached.size > 4) disconnect(attached.removeAt(0).second)
        return own
    }

    fun resume() = ctx?.let { resume(it) } ?: Unit

    fun apply(new: EffectsConfig) {
        effects = new
        if (ctx != null) build()
    }

    fun setVolume(value: Double) {
        volume = value
        master?.let { setGain(it, value) }
    }

    /** Ramps [own] to [target] over [ms]; then [after], if given. */
    fun fade(own: JsAny, target: Double, ms: Int, after: (() -> Unit)? = null) {
        val c = ctx ?: return after?.invoke() ?: Unit
        rampTo(c, own, target, ms / 1000.0)
        if (after != null) later(ms, after)
    }

    fun set(own: JsAny, value: Double) = setGain(own, value)

    private fun build() {
        val c = ctx ?: return
        val input = bus ?: return
        disconnect(input)
        chain.forEach(::disconnect)
        chain.clear()
        var at: JsAny = input
        fun add(node: JsAny) {
            connect(at, node)
            chain += node
            at = node
        }
        fun addStage(stage: JsAny) {
            connect(at, inputOf(stage))
            chain += inputOf(stage)
            chain += outputOf(stage)
            at = outputOf(stage)
        }
        val eq = effects.eq
        if (eq.enabled) {
            if (eq.preampDb != 0f) add(gainNode(c, dbToGain(eq.preampDb.toDouble())))
            for (band in eq.bands) {
                val type = when (band.kind) {
                    FilterKind.PEAK -> "peaking"
                    FilterKind.LOW_SHELF -> "lowshelf"
                    FilterKind.HIGH_SHELF -> "highshelf"
                }
                if (band.gainDb != 0f) add(biquad(c, type, band.freqHz.toDouble(), band.gainDb.toDouble(), band.q.toDouble()))
            }
        }
        if (effects.autoEq.enabled && effects.autoEq.peq.isNotBlank()) {
            val (preamp, filters) = parsePeq(effects.autoEq.peq)
            if (preamp != 0.0) add(gainNode(c, dbToGain(preamp)))
            filters.forEach { add(biquad(c, it.type, it.freqHz, it.gainDb, it.q)) }
        }
        val stereo = effects.stereo
        if (stereo.voiceReduction) addStage(voiceReduction(c))
        else if (stereo.mono) add(monoNode(c))
        if (stereo.crossfeed && !stereo.mono && !stereo.voiceReduction) addStage(crossfeed(c))
        if (stereo.balance != 0) add(panner(c, stereo.balance / 100.0))
        val out = gainNode(c, volume)
        add(out)
        master = out
        connect(out, destination(c))
    }

    private fun dbToGain(db: Double): Double = kotlin.math.exp(db / 20.0 * kotlin.math.ln(10.0))
}
