package com.lightbrowser.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.lightbrowser.data.ScriptStorage
import com.lightbrowser.data.UserScript
import com.lightbrowser.databinding.FragmentScriptsBinding
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.lightbrowser.R

class ScriptsFragment : Fragment() {
    private var _b: FragmentScriptsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: Adapter

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentScriptsBinding.inflate(inflater, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        adapter = Adapter(
            onToggle = { sc, en ->
                ScriptStorage.update(requireContext(), sc.copy(enabled = en))
                refresh()
            },
            onEdit = { sc -> showEditor(sc) },
            onDelete = { sc ->
                AlertDialog.Builder(requireContext()).setTitle("Delete ${sc.name}?").setPositiveButton("Delete") { _, _ ->
                    ScriptStorage.delete(requireContext(), sc.id); refresh()
                }.setNegativeButton("Cancel", null).show()
            }
        )
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter
        b.btnAdd.setOnClickListener { showEditor(null) }
        refresh()
    }

    private fun refresh() {
        val list = ScriptStorage.all(requireContext())
        adapter.submit(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditor(existing: UserScript?) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val etName = EditText(ctx).apply { hint = "Name (or leave empty to parse from // ==UserScript== @name)"; setText(existing?.name ?: "") }
        val etCode = EditText(ctx).apply {
            hint = "Paste full Violentmonkey script here (includes // ==UserScript== block). Example:\n// ==UserScript==\n// @name My Script\n// @match *://*/*\n// ==/UserScript==\n(function(){alert('hi')})()"
            setText(existing?.code ?: "")
            minLines = 10; isSingleLine = false
        }
        container.addView(etName)
        container.addView(etCode)
        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Add Userscript" else "Edit ${existing.name}")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val raw = etCode.text.toString()
                if (raw.isBlank()) { Toast.makeText(ctx, "Code empty", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val parsed = UserScript.fromCode(raw)
                val name = etName.text.toString().ifBlank { parsed.name }
                val toSave = (existing ?: parsed).copy(name = name, code = raw, description = parsed.description, matches = parsed.matches, runAt = parsed.runAt)
                if (existing == null) ScriptStorage.add(ctx, toSave) else ScriptStorage.update(ctx, toSave)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test match") { _, _ ->
                val p = UserScript.fromCode(etCode.text.toString())
                Toast.makeText(ctx, "matches=${p.matches} runAt=${p.runAt}", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private class Adapter(
        val onToggle: (UserScript, Boolean) -> Unit,
        val onEdit: (UserScript) -> Unit,
        val onDelete: (UserScript) -> Unit
    ) : RecyclerView.Adapter<Adapter.H>() {
        private var items: List<UserScript> = emptyList()
        fun submit(l: List<UserScript>) { items = l; notifyDataSetChanged() }
        class H(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvDesc: TextView = v.findViewById(R.id.tvDesc)
            val tvMatches: TextView = v.findViewById(R.id.tvMatches)
            val sw = v.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEnabled)
            val btnEdit: View = v.findViewById(R.id.btnEdit)
            val btnDelete: View = v.findViewById(R.id.btnDelete)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): H {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_script, p, false)
            return H(v)
        }
        override fun onBindViewHolder(h: H, i: Int) {
            val sc = items[i]
            h.tvName.text = sc.name
            h.tvDesc.text = if (sc.description.isNotBlank()) sc.description else sc.code.take(120).replace("\n", " ")
            h.tvMatches.text = if (sc.matches.isEmpty()) "match: <all_urls> · run-at: ${sc.runAt}" else "match: ${sc.matches.joinToString(", ")} · ${sc.runAt}"
            h.sw.setOnCheckedChangeListener(null)
            h.sw.isChecked = sc.enabled
            h.sw.setOnCheckedChangeListener { _, b -> onToggle(sc, b) }
            h.btnEdit.setOnClickListener { onEdit(sc) }
            h.btnDelete.setOnClickListener { onDelete(sc) }
        }
        override fun getItemCount() = items.size
    }
}
