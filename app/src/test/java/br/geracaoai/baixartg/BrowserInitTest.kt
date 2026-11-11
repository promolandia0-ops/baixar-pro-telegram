package br.geracaoai.baixartg

import android.app.Activity
import android.app.Application
import br.geracaoai.baixartg.ui.BrowserHolder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BrowserInitTest {
    @Test fun activityContextPrefersActivityAndNotApplication() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ctx = BrowserHolder.activityContext(activity)
        assertTrue("O WebView tem que nascer no contexto da tela", ctx is Activity)
        assertFalse("Application context derruba o WebView em vários aparelhos", ctx is Application)
        assertSame(activity, ctx)
        // o contexto da aplicação, sozinho, não é promovido a Activity
        val app = activity.applicationContext
        assertFalse(BrowserHolder.activityContext(app) is Activity)
    }
}
