package com.wboelens.polarrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Sul telefono: marchio.NOME_APP letto da Chaquopy == BuildConfig.APP_NAME. */
@RunWith(AndroidJUnit4::class)
class MarchioChaquopyTest {
  @Test
  fun nomeUgualeTramiteChaquopy() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
    assertEquals(BuildConfig.APP_NAME, Python.getInstance().getModule("marchio").get("NOME_APP").toString())
  }
}
