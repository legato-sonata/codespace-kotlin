package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var isRecording = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenRecordChannel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    startRecording(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen & Audio Recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        isRecording = true

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        setupMuxerAndCodecs(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

        // Start reading threads
        coroutineScope.launch {
            recordVideo()
        }
        coroutineScope.launch {
            recordAudio()
        }
    }

    private fun setupMuxerAndCodecs(width: Int, height: Int, densityDpi: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Record_$timestamp.mp4")

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Video Codec Setup
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 5 * 1024 * 1024)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        val inputSurface = videoCodec?.createInputSurface()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecord",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
        videoCodec?.start()

        // Audio Codec & Record Setup
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormatObj = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormatObj)
                .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
                .setBufferSizeInBytes(minBufferSize)
                .build()
        }

        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        aFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        aFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        aFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)

        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec?.start()
        
        audioRecord?.startRecording()
    }

    private fun recordVideo() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording) {
            val encoderStatus = videoCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: break
            if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw RuntimeException("format changed twice")
                val newFormat = videoCodec?.outputFormat
                videoTrackIndex = muxer?.addTrack(newFormat!!) ?: -1
                startMuxerIfReady()
            } else if (encoderStatus >= 0) {
                val encodedData = videoCodec?.getOutputBuffer(encoderStatus)
                if (encodedData != null && muxerStarted) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                }
                videoCodec?.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun recordAudio() {
        val bufferInfo = MediaCodec.BufferInfo()
        val sampleRate = 44100
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        val audioBuffer = ByteArray(minBufferSize)

        var presentationTimeUs = 0L

        while (isRecording) {
            val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
            
            if (readBytes > 0) {
                val inputBufferIndex = audioCodec?.dequeueInputBuffer(10000) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = audioCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(audioBuffer, 0, readBytes)
                    val ptsUs = System.nanoTime() / 1000
                    if (presentationTimeUs == 0L) presentationTimeUs = ptsUs
                    audioCodec?.queueInputBuffer(inputBufferIndex, 0, readBytes, ptsUs, 0)
                }
            }

            var encoderStatus = audioCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
            while (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER && isRecording) {
                if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = audioCodec?.outputFormat
                    if (newFormat != null) {
                        audioTrackIndex = muxer?.addTrack(newFormat) ?: -1
                        startMuxerIfReady()
                    }
                } else if (encoderStatus >= 0) {
                    val encodedData = audioCodec?.getOutputBuffer(encoderStatus)
                    if (encodedData != null && muxerStarted) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    audioCodec?.releaseOutputBuffer(encoderStatus, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                encoderStatus = audioCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
            }
        }
    }

    @Synchronized
    private fun startMuxerIfReady() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer?.start()
            muxerStarted = true
        }
    }

    private fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            
            videoCodec?.stop()
            videoCodec?.release()
            
            audioCodec?.stop()
            audioCodec?.release()
            
            if (muxerStarted) {
                muxer?.stop()
                muxer?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        virtualDisplay?.release()
        mediaProjection?.stop()
        
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
