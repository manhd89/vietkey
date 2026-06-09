package com.vietsmart.key

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import android.widget.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 80, 64, 80)
        }

        root.addView(TextView(this).apply {
            text = "⌨️ VietSmart Keyboard"
            textSize = 26f
            setPadding(0, 0, 0, 32)
        })

        root.addView(TextView(this).apply {
            text = "Bước 1: Bật bàn phím trong Cài đặt hệ thống"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        })

        root.addView(Button(this).apply {
            text = "Mở Cài đặt ngôn ngữ & đầu vào"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        root.addView(TextView(this).apply {
            text = "\nBước 2: Chọn VietSmart Keyboard làm bàn phím mặc định"
            textSize = 16f
            setPadding(0, 24, 0, 16)
        })

        root.addView(Button(this).apply {
            text = "Đặt làm bàn phím mặc định"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            }
        })

        root.addView(TextView(this).apply {
            text = """

✅ Sau khi cài xong, mở bất kỳ ứng dụng có ô nhập liệu và gõ tiếng Việt!

── Telex ──────────────────────
  s = sắc (á)    f = huyền (à)
  r = hỏi (ả)    x = ngã (ã)
  j = nặng (ạ)   z = xoá dấu
  aa = â   aw = ă   ee = ê
  oo = ô   ow = ơ   uw = ư
  dd = đ   w = ư (standalone)

── VNI ────────────────────────
  1=sắc  2=huyền  3=hỏi
  4=ngã  5=nặng   0=xoá
  6=â/ê/ô   7/8=ơ/ư/ă   9=đ

── VIQR ───────────────────────
  '=sắc  `=huyền  ?=hỏi
  ~=ngã  .=nặng
  ^=â/ê/ô   (=ă   +=ơ/ư

── Chuyển bộ gõ ───────────────
  Nhấn nút [Telex/VNI/VIQR] trên bàn phím để chuyển
            """.trimIndent()
            textSize = 13f
            setPadding(0, 32, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
