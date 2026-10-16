package io.voxphilosophica

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPlaybackRegressionTest {
    @Test
    fun playbackStatusUpdatesCurrentPassageWithoutCrashingReader() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val book = BookStore(context).save(
            "Playback Crash Regression", listOf(Chapter("Chapter 1", "Kokoro reads this sentence."))
        )
        val open = Intent(context, ReaderActivity::class.java).putExtra("bookId", book.id)
        val controller = Robolectric.buildActivity(ReaderActivity::class.java, open).setup()
        val activity = controller.get()

        // The v0.4.0 reader crashed here because 'excerpt' was never initialized.
        activity.sendBroadcast(
            Intent(NarrationService.EVENT)
                .setPackage(context.packageName)
                .putExtra("bookId", book.id)
                .putExtra("message", "Preparing audiobook…")
                .putExtra("segment", "Kokoro reads this sentence.")
                .putExtra("chunk", 0)
                .putExtra("total", 1)
                .putExtra("chapter", 0)
                .putExtra("paused", false)
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val excerptField = ReaderActivity::class.java.getDeclaredField("excerpt")
        excerptField.isAccessible = true
        val excerpt = excerptField.get(activity) as? TextView
        assertNotNull("Playback passage view must exist", excerpt)
        assertEquals("Kokoro reads this sentence.", excerpt!!.text.toString())
        assertFalse("Playback broadcast must not close the reader", activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
