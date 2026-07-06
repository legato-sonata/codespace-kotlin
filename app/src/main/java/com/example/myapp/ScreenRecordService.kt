package com.example.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var playbackCaptureUsable = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var isRecording = false

    private var videoJob: Job? = null
    private var audioJob: Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenRecordChannel"
        private const val TAG = "ScreenRecordService"
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by the system; stopping recording.")
                stopRecording()
            }
        }
        mediaProjection?.registerCallback(mediaProjectionCallback!!, Handler(Looper.getMainLooper()))

        isRecording = true

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        setupMuxerAndCodecs(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

        videoJob = coroutineScope.launch { recordVideo() }
        if (playbackCaptureUsable) {
            audioJob = coroutineScope.launch { recordAudio() }
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

        // Audio Codec & Record Setup - changed to Stereo for broader compatibility
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
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

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    playbackCaptureUsable = true
                    Log.d(TAG, "Playback-capture AudioRecord initialized OK.")
                } else {
                    Log.e(TAG, "AudioRecord failed to initialize (state=${audioRecord?.state}).")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up playback capture", e)
                audioRecord = null
                playbackCaptureUsable = false
            }
        }

        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, "Internal Audio Usable: $playbackCaptureUsable", android.widget.Toast.LENGTH_LONG).show()
        }

        if (playbackCaptureUsable) {
            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            aFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            aFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            aFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)

            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec?.start()

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord.startRecording() did not actually start recording.")
            }
        }
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
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2
        val audioBuffer = ByteArray(minBufferSize)

        var loggedFirstRead = false
        var loggedSilenceWarning = false
        var totalBytesRead = 0L

        while (isRecording) {
            val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0

            if (readBytes < 0) {
                Log.e(TAG, "AudioRecord.read() returned error code $readBytes")
            } else if (readBytes > 0) {
                if (!loggedFirstRead) {
                    Log.d(TAG, "First audio buffer captured ($readBytes bytes) - pipeline is alive.")
                    loggedFirstRead = true
                }
                totalBytesRead += readBytes

                if (!loggedSilenceWarning && totalBytesRead > sampleRate * 4) {
                    val allZero = audioBuffer.take(readBytes).all { it == 0.toByte() }
                    if (allZero) {
                        Log.w(
                            TAG,
                            "Captured audio is all-zero silence. The foreground app most " +
                                "likely disallows playback capture."
                        )
                        Handler(Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(this@ScreenRecordService, "WARNING: App is blocking audio capture (silence)", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    loggedSilenceWarning = true
                }

                val inputBufferIndex = audioCodec?.dequeueInputBuffer(10000) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = audioCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(audioBuffer, 0, readBytes)
                    val ptsUs = System.nanoTime() / 1000
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
        if (!muxerStarted) {
            val audioReady = if (playbackCaptureUsable) audioTrackIndex >= 0 else true
            val videoReady = videoTrackIndex >= 0
            
            if (videoReady && audioReady) {
                muxer?.start()
                muxerStarted = true
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        coroutineScope.launch {
            videoJob?.join()
            audioJob?.join()

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
                Log.e(TAG, "Error while stopping recording", e)
            }

            virtualDisplay?.release()
            mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
            mediaProjection?.stop()

            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            playbackCaptureUsable = false
        }
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
