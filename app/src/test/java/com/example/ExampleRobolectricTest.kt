package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.color.LutGenerator
import com.example.domain.model.LutPreset
import com.example.portrait.GuidedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("LumenCam", appName)
  }

  @Test
  fun `lut generator produces 256x16 3d lut textures and valid kelvin gains`() {
    LutPreset.entries.forEach { preset ->
      val lut = LutGenerator.getLutBitmap(preset)
      assertEquals(256, lut.width)
      assertEquals(16, lut.height)
    }
    val tungsten = LutGenerator.kelvinToRelativeRgb(3200)
    val shade = LutGenerator.kelvinToRelativeRgb(7500)
    assertTrue(tungsten.third > tungsten.first)
    assertTrue(shade.first > shade.third)
  }

  @Test
  fun `guided filter preserves normalized alpha range`() {
    val w = 16
    val h = 16
    val guide = FloatArray(w * h) { idx ->
      idx.toFloat() / (w * h)
    }
    val mask = FloatArray(w * h) { idx -> if (idx % w in 4..11) 1f else 0f }
    val refined = GuidedFilter.runGuidedFilter(guide, mask, w, h, radius = 2, eps = 0.01f)
    assertEquals(w * h, refined.size)
    assertTrue(refined.all { it in 0f..1f })
  }
}
