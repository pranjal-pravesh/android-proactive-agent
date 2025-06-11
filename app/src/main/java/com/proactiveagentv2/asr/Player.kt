package com.proactiveagentv2.asr

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

class Player(private val context: Context?) {
    interface PlaybackListener {
        fun onPlaybackStarted()
        fun onPlaybackStopped()
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playbackListener: PlaybackListener? = null

    fun setListener(listener: PlaybackListener?) {
        this.playbackListener = listener
    }

    fun initializePlayer(filePath: String?) {
        val waveFileUri = Uri.parse(filePath)
        if (waveFileUri == null || context == null) {
            Log.e("WavePlayer", "File path or context is null. Cannot initialize MediaPlayer.")
            return
        }

        releaseMediaPlayer() // Release any existing MediaPlayer

        mediaPlayer = MediaPlayer.create(context, waveFileUri)
        if (mediaPlayer != null) {
            mediaPlayer!!.setOnPreparedListener { mp: MediaPlayer? ->
                if (playbackListener != null) {
                    playbackListener!!.onPlaybackStarted()
                }
                mediaPlayer!!.start()
            }

            mediaPlayer!!.setOnCompletionListener { mp: MediaPlayer? ->
                if (playbackListener != null) {
                    playbackListener!!.onPlaybackStopped()
                }
                releaseMediaPlayer()
            }
        } else {
            if (playbackListener != null) {
                playbackListener!!.onPlaybackStopped()
            }
        }
    }

    fun startPlayback() {
        if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
            mediaPlayer!!.start()
            if (playbackListener != null) {
                playbackListener!!.onPlaybackStarted()
            }
        }
    }

    fun stopPlayback() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            if (playbackListener != null) {
                playbackListener!!.onPlaybackStopped()
            }
            releaseMediaPlayer()
        }
    }

    val isPlaying: Boolean
        get() = mediaPlayer != null && mediaPlayer!!.isPlaying

    private fun releaseMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }
}
