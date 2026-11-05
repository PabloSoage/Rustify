// sharedUi/src/iosMain/kotlin/com/varuna/rustify/ui/IosPresenter.kt
package com.varuna.rustify.ui

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/** Puts a system sheet (the share sheet, the document picker) over whatever is on screen. */
internal object IosPresenter {
    private fun top(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) vc = vc.presentedViewController ?: return vc
    }

    /** False when there is no window to present from. */
    fun present(sheet: UIViewController): Boolean {
        val from = top() ?: return false
        // An iPad shows these as popovers, which have to hang from something.
        sheet.popoverPresentationController?.sourceView = from.view
        from.presentViewController(sheet, animated = true, completion = null)
        return true
    }
}
