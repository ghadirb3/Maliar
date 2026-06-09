package com.persianai.assistant.services

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor

/**
 * Offline Haaniye TTS (piper/mimic3) loader.
 * Copies bundled assets to filesDir/haaniye, initializes ONNX session, performs a
 * lightweight grapheme→phoneme mapping to run the model, and writes PCM to a temp wav.
 */
object HaaniyeManager {
    private const val TAG = "HaaniyeManager"
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var initialized = false
    private var tokenMap: Map<Char, Int> = emptyMap()
    private var sampleRate = 22050

    /**
     * Attempt to synthesize speech with offline Haaniye model.
     * Returns true if synthesis handled; false to allow caller fallback.
     * DISABLED: Haaniye model removed to reduce app size
     */
    fun speak(context: Context, text: String): Boolean {
        Log.d(TAG, "Haaniye TTS disabled - using online TTS instead")
        return false
    }

    private fun ensureModelCopied(context: Context) {
        val dest = File(context.filesDir, "haaniye")
        if (dest.exists() && dest.isDirectory && dest.list()?.isNotEmpty() == true) return

        val assetBase = "haaniye"
        try {
            copyAssetDir(context, assetBase, dest)
            Log.d(TAG, "Haaniye assets copied to ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy Haaniye assets: ${e.message}")
        }
    }

    private fun copyAssetDir(context: Context, assetDir: String, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        val assets = context.assets.list(assetDir) ?: return
        for (name in assets) {
            val path = "$assetDir/$name"
            val outFile = File(destDir, name)
            val sub = context.assets.list(path)
            if (sub.isNullOrEmpty()) {
                copyAssetFile(context, path, outFile)
            } else {
                copyAssetDir(context, path, outFile)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, outFile: File) {
        context.assets.open(assetPath).use { input ->
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun initializeSession(context: Context) {
        try {
            val modelFile = File(context.filesDir, "haaniye/fa-haaniye_low.onnx")
            if (!modelFile.exists()) {
                Log.w(TAG, "Model file missing: ${modelFile.absolutePath}")
                return
            }
            ortEnv = OrtEnvironment.getEnvironment()
            ortSession = ortEnv?.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            initialized = ortSession != null
            Log.d(TAG, "Haaniye ONNX session initialized: $initialized")

            tokenMap = loadTokens(context)
            sampleRate = 22050 // from model json
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ONNX session: ${e.message}")
            initialized = false
        }
    }

    private fun loadTokens(context: Context): Map<Char, Int> {
        return try {
            val dest = File(context.filesDir, "haaniye/tokens.txt")
            if (!dest.exists()) {
                copyAssetFile(context, "haaniye/tokens.txt", dest)
            }
            dest.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size >= 2 && parts[0].isNotEmpty()) {
                        parts[0].first() to parts[1].toInt()
                    } else null
                }.toMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading tokens: ${e.message}")
            emptyMap()
        }
    }

    private fun phonemizeSimple(input: String): IntArray {
        // Very lightweight mapping Persian characters to approximate phonemes used in tokens.txt
        val map = mapOf(
            'ا' to 'a', 'آ' to 'a', 'ب' to 'b', 'پ' to 'p', 'ت' to 't', 'ث' to 's',
            'ج' to 'j', 'چ' to 't', // t + ʃ approximated as 't'
            'ح' to 'h', 'خ' to 'x', 'د' to 'd', 'ذ' to 'z', 'ر' to 'r', 'ز' to 'z',
            'ژ' to 'ʒ', 'س' to 's', 'ش' to 'ʃ', 'ص' to 's', 'ض' to 'z',
            'ط' to 't', 'ظ' to 'z', 'ع' to 'ʔ', 'غ' to 'q', 'ق' to 'q',
            'ف' to 'f', 'ک' to 'k', 'گ' to 'ɡ', 'ل' to 'l', 'م' to 'm',
            'ن' to 'n', 'و' to 'u', 'ه' to 'h', 'ی' to 'i', 'ء' to 'ʔ'
        )
        val tokens = mutableListOf<Int>()
        // start token '^' -> id 1, pad '_' -> id 0, end '$' -> id 2
        val bos = tokenMap['^'] ?: 1
        val eos = tokenMap['$'] ?: 2
        tokens.add(bos)
        input.forEach { ch ->
            val normalized = map[ch] ?: ch.lowercaseChar()
            val id = tokenMap[normalized] ?: tokenMap[' '] ?: 0
            tokens.add(id)
        }
        tokens.add(eos)
        return tokens.toIntArray()
    }

    private fun synthesizeToFile(context: Context, text: String): File? {
        try {
            val env = ortEnv ?: return null
            val session = ortSession ?: return null
            val ids = phonemizeSimple(text)
            if (ids.isEmpty()) return null
            val inputLen = ids.size

            val inputs = mutableMapOf<String, OnnxTensor>()
            session.inputInfo.keys.forEach { name ->
                when (name) {
                    "input" -> {
                        val shape = longArrayOf(1, inputLen.toLong())
                        val buf = LongBuffer.wrap(ids.map { it.toLong() }.toLongArray())
                        inputs[name] = OnnxTensor.createTensor(env, buf, shape)
                    }
                    "input_lengths" -> {
                        val buf = LongBuffer.wrap(longArrayOf(inputLen.toLong()))
                        inputs[name] = OnnxTensor.createTensor(env, buf, longArrayOf(1))
                    }
                    "scales" -> {
                        // noise_scale, length_scale, noise_w (rank-1 vector of size 3)
                        val buf = FloatBuffer.wrap(floatArrayOf(0.333f, 1.0f, 0.333f))
                        inputs[name] = OnnxTensor.createTensor(env, buf, longArrayOf(3))
                    }
                    "sid" -> {
                        val buf = LongBuffer.wrap(longArrayOf(0))
                        inputs[name] = OnnxTensor.createTensor(env, buf, longArrayOf(1))
                    }
                }
            }

            val result = session.run(inputs)
            val outName = session.outputInfo.keys.firstOrNull() ?: return null
            val outputTensor = result[outName] as? OnnxTensor ?: return null

            val audioFloats: FloatArray? = when (val v = outputTensor.value) {
                is FloatArray -> v
                is Array<*> -> (v.firstOrNull() as? FloatArray)
                else -> {
                    val fb = outputTensor.floatBuffer
                    fb?.let {
                        val arr = FloatArray(it.remaining())
                        it.get(arr)
                        arr
                    }
                }
            }
            if (audioFloats == null || audioFloats.isEmpty()) {
                Log.w(TAG, "synthesizeToFile: empty audio output for text length=$inputLen")
                result.close()
                inputs.values.forEach { it.close() }
                return null
            }

            val pcm = floatsToPcm16(audioFloats)
            val wavFile = File(context.cacheDir, "haaniye_${System.currentTimeMillis()}.wav")
            writeWav(wavFile, pcm, sampleRate)
            result.close()
            inputs.values.forEach { it.close() }
            return wavFile
        } catch (e: Exception) {
            Log.e(TAG, "synthesizeToFile failed: ${e.message}")
            return null
        }
    }

    private fun floatsToPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) {
            val clamped = max(-1.0f, min(1.0f, f))
            val s = (clamped * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(s)
        }
        return buffer.array()
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val byteRate = sampleRate * 2
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        FileOutputStream(file).use { out ->
            out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            out.write(intToLe(totalDataLen))
            out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
            out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            out.write(intToLe(16)) // Subchunk1Size for PCM
            out.write(shortToLe(1)) // PCM
            out.write(shortToLe(1)) // mono
            out.write(intToLe(sampleRate))
            out.write(intToLe(byteRate))
            out.write(shortToLe(2)) // block align
            out.write(shortToLe(16)) // bits per sample
            out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            out.write(intToLe(dataSize))
            out.write(pcm)
        }
    }

    private fun intToLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte()
    )

    private fun playWithMediaPlayer(context: Context, wavFile: File) {
        try {
            val player = android.media.MediaPlayer()
            player.setDataSource(wavFile.absolutePath)
            player.setOnCompletionListener {
                try { it.reset(); it.release() } catch (_: Exception) {}
            }
            player.setOnErrorListener { mp, what, extra ->
                try { mp.reset(); mp.release() } catch (_: Exception) {}
                Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play wav: ${e.message}")
        }
    }
}
