package app.pwhs.blockads.receiver

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskerReceiverManifestTest {

    @Ignore("guarding TaskerReceiver with a permission breaks existing Tasker setups; needs a product decision")
    @Test
    fun `exported Tasker receiver requires a permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, TaskerReceiver::class.java), 0
        )
        if (info.exported) {
            assertNotNull("TaskerReceiver is exported with no permission; any app can stop blocking", info.permission)
        }
    }
}
