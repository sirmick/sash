package s1m.hwfido2provider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import s1m.hwfido2provider.ui.MainState
import s1m.hwfido2provider.ui.MainUi
import s1m.hwfido2provider.ui.MainViewModel
import s1m.hwfido2provider.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = MainState.from(this)
            val vm = MainViewModel(state, { this })
            AppTheme {
                MainUi(vm)
            }
        }
    }
}
