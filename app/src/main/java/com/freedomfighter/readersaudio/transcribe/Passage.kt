package com.freedomfighter.readersaudio.transcribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.freedomfighter.readers.speech.audio.Pcm
import com.freedomfighter.readers.speech.audio.Resample
import java.nio.ByteOrder

/**
 * A passage of a file, decoded on its own: [n] samples at 16 kHz mono from [offset] (in 16 kHz
 * samples). Finding the language looks at a few half-minutes spread over the whole file; an
 * audiobook runs to hours, and decoding it from the start to reach each one would cost more than
 * the transcription's first piece. So the decoder jumps there. Where the format cannot jump that
 * far (it lands well before), the passage is taken from where it landed: still speech from the
 * file, which is all the guess needs.
 */
object Passage {
    fun read(ctx: Context, uri: Uri, offset: Long, n: Int, cancelled: () -> Boolean): FloatArray {
        val startUs = offset * 1_000_000L / 16_000
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        if (track == null) { ex.release(); error("no audio track") }
        ex.selectTrack(track)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        var out = FloatArray(0)
        var size = 0
        // Samples before the passage are skipped only when the jump landed close to it.
        var skip = -1L
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (cancelled()) throw Stopped()
                val want = n.toLong() * rate / 16_000 + rate / 10
                if (size >= want) break
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val k = ex.readSampleData(buf, 0)
                        if (k < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, k, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (o >= 0) {
                    if (skip < 0) {
                        val early = startUs - info.presentationTimeUs
                        skip = if (early in 1..10_000_000L) early * rate / 1_000_000L else 0L
                    }
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    val frames: Int
                    val get: () -> Float
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) { val fb = buf.asFloatBuffer(); frames = fb.remaining() / channels; get = { fb.get() } }
                    else { val sb = buf.asShortBuffer(); frames = sb.remaining() / channels; get = { sb.get() / 32768f } }
                    if (out.size < size + frames) out = out.copyOf(maxOf(out.size * 2, size + frames))
                    for (k in 0 until frames) {
                        var v = 0f
                        for (c in 0 until channels) v += get()
                        if (skip > 0) { skip--; continue }
                        out[size++] = v / channels
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        val pcm = Resample.to(Pcm(out.copyOf(size), rate), 16_000).samples
        return if (pcm.size == n) pcm else pcm.copyOf(n)
    }

    /** Thrown out of a read when the person stops the work. */
    class Stopped : Exception()
}
