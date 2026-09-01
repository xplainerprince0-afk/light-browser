package com.lightbrowser.ui

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lightbrowser.R
import com.lightbrowser.databinding.FragmentMusicBinding
import java.io.File

class MusicPlayerFragment : Fragment() {
    private var _b: FragmentMusicBinding? = null
    private val b get() = _b!!
    private var player: MediaPlayer? = null
    private var queue: List<File> = emptyList()
    private var currentIndex = -1
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying && !isSeeking) {
                val pos = p.currentPosition
                val dur = p.duration
                b.seekBar.max = if (dur > 0) dur else 100
                b.seekBar.progress = pos
                b.tvCurrent.text = fmt(pos)
                b.tvDuration.text = fmt(dur)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMusicBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.btnScan.setOnClickListener { scan() }
        b.btnStop.setOnClickListener { stop() }
        b.btnPlay.setOnClickListener { toggle() }
        b.btnPrev.setOnClickListener { prev() }
        b.btnNext.setOnClickListener { next() }
        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) b.tvCurrent.text = fmt(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                player?.seekTo(sb?.progress ?: 0)
            }
        })
        scan()
        handler.post(updateRunnable)
    }

    private fun scan() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("mp3","m4a","aac","ogg","wav","flac","opus") }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (_: Exception) { emptyList<File>() }
        queue = files
        b.empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        b.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val f = files[i]
                h.itemView.findViewById<TextView>(R.id.tvTitle).text = f.name
                h.itemView.findViewById<TextView>(R.id.tvStatus).text = "${f.length()/1024} KB • ${if (i == currentIndex) "▶ Playing" else "Tap to play"}"
                h.itemView.alpha = if (i == currentIndex) 1f else 0.85f
                h.itemView.setOnClickListener { playAt(i) }
            }
            override fun getItemCount() = files.size
        }
        if (files.isNotEmpty() && currentIndex == -1) {
            b.tvTitle.text = "${files.size} tracks found"
        }
    }

    private fun playAt(idx: Int) {
        if (idx < 0 || idx >= queue.size) return
        try {
            stopPlayer()
            currentIndex = idx
            val f = queue[idx]
            player = MediaPlayer().apply {
                setDataSource(requireContext(), Uri.fromFile(f))
                setOnPreparedListener {
                    start()
                    b.btnPlay.text = "⏸"
                    b.tvTitle.text = f.nameWithoutExtension
                    b.tvArtist.text = f.parentFile?.name ?: "Downloads"
                    b.tvDuration.text = fmt(duration)
                }
                setOnCompletionListener { next() }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(requireContext(), "Error $what/$extra", Toast.LENGTH_SHORT).show()
                    true
                }
                prepareAsync()
            }
            b.recycler.adapter?.notifyDataSetChanged()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Play failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggle() {
        val p = player
        if (p == null) {
            if (queue.isNotEmpty()) playAt(0) else Toast.makeText(requireContext(), "Scan first", Toast.LENGTH_SHORT).show()
            return
        }
        if (p.isPlaying) { p.pause(); b.btnPlay.text = "▶" }
        else { p.start(); b.btnPlay.text = "⏸" }
    }

    private fun prev() {
        if (queue.isEmpty()) return
        val next = if (currentIndex <= 0) queue.size - 1 else currentIndex - 1
        playAt(next)
    }

    private fun next() {
        if (queue.isEmpty()) return
        val next = if (currentIndex >= queue.size - 1) 0 else currentIndex + 1
        playAt(next)
    }

    private fun stop() {
        stopPlayer()
        b.btnPlay.text = "▶"
        b.tvTitle.text = "Stopped"
        b.seekBar.progress = 0
        b.tvCurrent.text = "0:00"
        currentIndex = -1
        b.recycler.adapter?.notifyDataSetChanged()
    }

    private fun stopPlayer() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun fmt(ms: Int): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // pause to save memory for scraper when not visible
            if (player?.isPlaying == true) {
                player?.pause()
                b.btnPlay.text = "▶"
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(updateRunnable)
        stopPlayer()
        _b = null
        super.onDestroyView()
    }
}
