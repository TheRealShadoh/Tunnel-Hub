package dev.tunnelhub.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/**
 * First-launch (and later "Edit tabs…") screen: a list of name + URL rows.
 * Saved with Sites.save; MainActivity rebuilds its tabs from that.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private var editing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_setup)
        val root = findViewById<View>(R.id.setupRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        editing = intent.getBooleanExtra(EXTRA_EDIT, false)
        val toolbar = findViewById<MaterialToolbar>(R.id.setupToolbar)
        toolbar.title = getString(if (editing) R.string.setup_title_edit else R.string.setup_title)
        if (editing) {
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.setNavigationOnClickListener { finish() }
        }

        rows = findViewById(R.id.rows)
        findViewById<MaterialButton>(R.id.addRow).setOnClickListener { addRow() }
        findViewById<MaterialButton>(R.id.save).setOnClickListener { save() }

        val existing = Sites.load(this)
        if (existing.isEmpty()) {
            addRow("Chat", ""); addRow("", "")
        } else existing.forEach { addRow(it.label, it.url, it.css) }
    }

    private fun addRow(label: String = "", url: String = "", css: String = "") {
        val row = LayoutInflater.from(this).inflate(R.layout.row_site, rows, false)
        row.findViewById<EditText>(R.id.siteName).setText(label)
        row.findViewById<EditText>(R.id.siteUrl).setText(url)
        val cssField = row.findViewById<EditText>(R.id.siteCss)
        val cssWrap = row.findViewById<View>(R.id.cssWrap)
        cssField.setText(css)
        cssWrap.visibility = if (css.isNotBlank()) View.VISIBLE else View.GONE
        row.findViewById<View>(R.id.advanced).setOnClickListener {
            cssWrap.visibility = if (cssWrap.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        row.findViewById<ImageButton>(R.id.remove).setOnClickListener { rows.removeView(row) }
        rows.addView(row)
    }

    private fun save() {
        val sites = mutableListOf<Site>()
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i)
            val nameField = row.findViewById<EditText>(R.id.siteName)
            val urlField = row.findViewById<EditText>(R.id.siteUrl)
            val url = Sites.normaliseUrl(urlField.text.toString())
            val name = nameField.text.toString().trim()
            if (url == null) {
                if (name.isEmpty() && urlField.text.isBlank()) continue // empty row, skip
                urlField.error = getString(R.string.setup_bad_url); urlField.requestFocus(); return
            }
            val label = name.ifEmpty { Site("", "", url).host.substringBefore('.').replaceFirstChar { it.uppercase() } }
            val css = row.findViewById<EditText>(R.id.siteCss).text.toString().trim()
            sites += Site("site$i", label, url, css)
        }
        if (sites.isEmpty()) {
            Snackbar.make(rows, R.string.setup_need_one, Snackbar.LENGTH_SHORT).show(); return
        }
        Sites.save(this, sites)
        if (!editing) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_EDIT = "edit"
    }
}
