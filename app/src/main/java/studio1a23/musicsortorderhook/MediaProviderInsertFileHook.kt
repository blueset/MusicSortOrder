package studio1a23.musicsortorderhook

import android.content.ContentValues
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam


class HookMediaProvider : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "com.android.providers.media")
            return
        XposedBridge.log("Loaded app [package name]: " + lpparam.packageName)

        val c = lpparam.classLoader.loadClass("com.android.providers.media.MediaProvider")

        lateinit var databaseHelper: Class<*>

        for (cls in c.declaredClasses) {
            if (cls.simpleName == "DatabaseHelper") {
                databaseHelper = cls
            }
        }

        findAndHookMethod(
            c, // "com.android.providers.media.MediaProvider", lpparam.classLoader,
            "insertFile",
            databaseHelper,
            Int::class.java,
            Uri::class.java,
            ContentValues::class.java,
            Int::class.java,
            Boolean::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    XposedBridge.log(param?.args?.get(3).toString())
                }
            }
        )

//        for (i in c.declaredMethods) {
//            XposedBridge.log("Method: ${i.name}")
//        }
    }
}