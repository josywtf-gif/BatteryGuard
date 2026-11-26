package com.example.batteryguard

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {
    private val labels = mapOf(
        "cpu_temp" to "Температура CPU",
        "battery_temp" to "Температура батареи",
        "ram" to "ОЗУ",
        "time" to "Время",
        "battery" to "Заряд",
        "voltage" to "Вольтаж и потребление"
    )
    private val icons = mapOf(
        "cpu_temp" to R.drawable.ic_cpu_temp,
        "battery_temp" to R.drawable.ic_battery_temp,
        "ram" to R.drawable.ic_ram,
        "time" to R.drawable.ic_clock,
        "battery" to R.drawable.ic_charge,
        "voltage" to R.drawable.ic_voltage
    )
    private lateinit var list: RecyclerView
    private lateinit var touchHelper: ItemTouchHelper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        list = findViewById(R.id.metricsList)
        setupMetrics()
        val metricsBody = findViewById<View>(R.id.metricsBody)
        val metricsArrow = findViewById<TextView>(R.id.metricsArrow)
        findViewById<View>(R.id.metricsHeader).setOnClickListener {
            val expanded = metricsBody.visibility != View.VISIBLE
            metricsBody.visibility = if (expanded) View.VISIBLE else View.GONE
            metricsArrow.text = if (expanded) "⌃" else "⌄"
        }

        val styleGroup = findViewById<RadioGroup>(R.id.overlayStyleGroup)
        styleGroup.removeAllViews()
        val current = Prefs.overlayStyle(this)
        val styleViewIds = LinkedHashMap<Int, String>()
        Prefs.STYLE_LABELS.forEach { (key, title) ->
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = title; isChecked = key == current }
            styleViewIds[rb.id] = key
            styleGroup.addView(rb, RadioGroup.LayoutParams(-1, -2))
        }
        styleGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setOverlayStyle(this, styleViewIds[checkedId] ?: "classic")
            sendRefresh()
        }
        val stylesArrow = findViewById<TextView>(R.id.stylesArrow)
        styleGroup.visibility = View.GONE
        findViewById<View>(R.id.stylesHeader).setOnClickListener {
            val expanded = styleGroup.visibility != View.VISIBLE
            styleGroup.visibility = if (expanded) View.VISIBLE else View.GONE
            stylesArrow.text = if (expanded) "⌃" else "⌄"
        }
        val warningHeader = findViewById<LinearLayout>(R.id.sensorWarningHeader)
        val warningBody = findViewById<View>(R.id.sensorWarningBody)
        val warningArrow = findViewById<TextView>(R.id.sensorWarningArrow)
        // Предупреждение всегда свёрнуто при новом открытии экрана настроек.
        warningBody.visibility = View.GONE
        warningArrow.text = "⌄"
        warningHeader.setOnClickListener {
            val expanded = warningBody.visibility != View.VISIBLE
            warningBody.visibility = if (expanded) View.VISIBLE else View.GONE
            warningArrow.text = if (expanded) "⌃" else "⌄"
        }

        val adjustmentsBody = findViewById<View>(R.id.adjustmentsBody)
        val adjustmentsArrow = findViewById<TextView>(R.id.adjustmentsArrow)
        // Раздел «Регулировки» всегда закрыт при открытии настроек.
        adjustmentsBody.visibility = View.GONE
        adjustmentsArrow.text = "⌄"
        findViewById<View>(R.id.adjustmentsHeader).setOnClickListener {
            val expanded = adjustmentsBody.visibility != View.VISIBLE
            adjustmentsBody.visibility = if (expanded) View.VISIBLE else View.GONE
            adjustmentsArrow.text = if (expanded) "⌃" else "⌄"
        }

        val size = findViewById<Slider>(R.id.sliderSize)
        val sizeText = findViewById<TextView>(R.id.tvSize)
        size.value = Prefs.size(this).toFloat().coerceIn(24f, 96f)
        sizeText.text = "${size.value.toInt()} dp"
        size.addOnChangeListener { _, value, fromUser ->
            sizeText.text = "${value.toInt()} dp"
            if (fromUser) { Prefs.setSize(this, value.toInt()); sendRefresh() }
        }
        val alpha = findViewById<Slider>(R.id.sliderAlpha)
        val alphaText = findViewById<TextView>(R.id.tvAlpha)
        alpha.value = Prefs.alpha(this).toFloat().coerceIn(30f, 100f)
        alphaText.text = "${alpha.value.toInt()}%"
        alpha.addOnChangeListener { _, value, fromUser ->
            alphaText.text = "${value.toInt()}%"
            if (fromUser) { Prefs.setAlpha(this, value.toInt()); sendRefresh() }
        }

        val positionX = findViewById<Slider>(R.id.sliderPositionX)
        val positionXText = findViewById<TextView>(R.id.tvPositionX)
        positionX.value = Prefs.posX(this).toFloat().coerceIn(-200f, 200f)
        positionXText.text = "${positionX.value.toInt()} dp"
        positionX.addOnChangeListener { _, value, fromUser ->
            positionXText.text = "${value.toInt()} dp"
            if (fromUser) { Prefs.setPos(this, value.toInt(), Prefs.posY(this)); sendRefresh() }
        }

        val positionY = findViewById<Slider>(R.id.sliderPositionY)
        val positionYText = findViewById<TextView>(R.id.tvPositionY)
        positionY.value = Prefs.posY(this).toFloat().coerceIn(-200f, 200f)
        positionYText.text = "${positionY.value.toInt()} dp"
        positionY.addOnChangeListener { _, value, fromUser ->
            positionYText.text = "${value.toInt()} dp"
            if (fromUser) { Prefs.setPos(this, Prefs.posX(this), value.toInt()); sendRefresh() }
        }
    }

    private fun setupMetrics() {
        val order = Prefs.order(this)
        val enabled = Prefs.metrics(this).toMutableSet()
        list.layoutManager = LinearLayoutManager(this)
        list.isNestedScrollingEnabled = false
        (list.itemAnimator as? DefaultItemAnimator)?.apply { moveDuration = 180; supportsChangeAnimations = false }
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = order.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(4)); minimumHeight = dp(54)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                }
                val icon = ImageView(this@SettingsActivity).apply {
                    setColorFilter(0xFF8AB4F8.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
                }
                val check = CheckBox(this@SettingsActivity).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                val handle = TextView(this@SettingsActivity).apply {
                    text = "☰"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFF8A94A6.toInt())
                    contentDescription = "Удерживайте и перетащите для изменения порядка"
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                }
                row.addView(icon); row.addView(check); row.addView(handle)
                return object : RecyclerView.ViewHolder(row) {}.also { holder ->
                    handle.setOnTouchListener { v, e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) { v.performClick(); touchHelper.startDrag(holder) }
                        false
                    }
                }
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val key = order[position]
                val row = holder.itemView as LinearLayout
                (row.getChildAt(0) as ImageView).apply { setImageResource(icons[key] ?: R.drawable.ic_thermo); contentDescription = labels[key] }
                (row.getChildAt(1) as CheckBox).apply {
                    setOnCheckedChangeListener(null)
                    text = labels[key]; isChecked = key in enabled
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) enabled.add(key) else enabled.remove(key)
                        Prefs.setMetrics(this@SettingsActivity, order.filter { it in enabled })
                        sendRefresh()
                    }
                }
            }
        }
        list.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition; val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                order.add(to, order.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.alpha(0.9f)?.setDuration(120)?.start()
            }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                Prefs.setOrder(this@SettingsActivity, order)
                Prefs.setMetrics(this@SettingsActivity, order.filter { it in enabled })
                sendRefresh()
            }
        })
        touchHelper.attachToRecyclerView(list)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun sendRefresh() { if (Prefs.isEnabled(this)) startService(android.content.Intent(this, MonitorService::class.java)) }
}
