package com.reduan.smartinfo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reduan.smartinfo.nfc.NfcController
import com.reduan.smartinfo.ui.screens.AboutScreen
import com.reduan.smartinfo.ui.screens.EditProfileScreen
import com.reduan.smartinfo.ui.screens.HomeScreen
import com.reduan.smartinfo.ui.screens.IntroScreen
import com.reduan.smartinfo.ui.screens.MyCardScreen
import com.reduan.smartinfo.ui.screens.NearbyScreen
import com.reduan.smartinfo.ui.screens.NfcScreen
import com.reduan.smartinfo.ui.screens.QrScreen
import com.reduan.smartinfo.ui.screens.Routes
import com.reduan.smartinfo.ui.screens.WifiShareScreen
import com.reduan.smartinfo.ui.theme.SmartInfoTheme

class MainActivity : ComponentActivity() {
    private lateinit var nfc: NfcController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcController(this)
        lifecycle.addObserver(nfc)
        setContent { SmartInfoTheme { AppRoot(nfc, showIntroInitially = savedInstanceState == null) } }
    }
}

@Composable
private fun AppRoot(nfc: NfcController, showIntroInitially: Boolean) {
    val vm: AppViewModel = viewModel()
    var showIntro by rememberSaveable { mutableStateOf(showIntroInitially) }

    // No background service in this version: stop Bluetooth and Wi-Fi sharing when the app leaves the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopAllSharing() }

    if (showIntro) { IntroScreen { showIntro = false }; return }

    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val openCard: () -> Unit = { nav.navigate(Routes.CARD) }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(vm) { nav.navigate(it) } }
        composable(Routes.EDIT) { EditProfileScreen(vm, back) }
        composable(Routes.CARD) { MyCardScreen(vm, onEdit = { nav.navigate(Routes.EDIT) }, onBack = back) }
        composable(Routes.QR) { QrScreen(vm, openCard, back) }
        composable(Routes.NFC) { NfcScreen(vm, nfc, openCard, back) }
        composable(Routes.NEARBY) { NearbyScreen(vm, veryNearOnly = false, onOpenCard = openCard, onBack = back) }
        composable(Routes.VERY_NEAR) { NearbyScreen(vm, veryNearOnly = true, onOpenCard = openCard, onBack = back) }
        composable(Routes.WIFI) { WifiShareScreen(vm, openCard, back) }
        composable(Routes.ABOUT) { AboutScreen(back) }
    }
}
