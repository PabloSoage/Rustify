//! The desktop window's frame in the app's colours.
//!
//! Compose for desktop draws inside a native window, and on Windows that window's title bar is the
//! system's light one above an app that is `#121212` everywhere else. DWM can paint it instead of
//! the window being made undecorated and given a hand-drawn bar: snapping, the shadow, the
//! maximise flyout and dragging between screens all stay the system's.
//!
//! Linux and macOS draw nothing here: their frames come from the window manager and follow the
//! system's dark theme already.

/// The app's background, `#121212`, as DWM wants a colour: `0x00BBGGRR`.
#[cfg(windows)]
const CAPTION: u32 = 0x0012_1212;
/// The title's text, white.
#[cfg(windows)]
const TEXT: u32 = 0x00FF_FFFF;

/// Gives the window `hwnd` a dark frame. Returns whether DWM took it: Windows 10 before 20H1 knows
/// none of these attributes and keeps its frame, which is not an error worth more than a log line.
#[cfg(windows)]
pub fn dark_frame(hwnd: isize) -> bool {
    use windows_sys::Win32::Graphics::Dwm::{
        DwmSetWindowAttribute, DWMWA_CAPTION_COLOR, DWMWA_TEXT_COLOR, DWMWA_USE_IMMERSIVE_DARK_MODE,
    };
    if hwnd == 0 {
        return false;
    }
    let set = |attribute: u32, value: u32| -> bool {
        // SAFETY: `hwnd` is the caller's live top-level window and `value` outlives the call, which
        // reads exactly `size_of::<u32>()` bytes from it.
        unsafe {
            DwmSetWindowAttribute(
                hwnd as _,
                attribute,
                &value as *const u32 as *const core::ffi::c_void,
                core::mem::size_of::<u32>() as u32,
            ) >= 0
        }
    };
    // Dark mode first (Windows 10 20H1 and later): it darkens the buttons too. The colours after it
    // (Windows 11) match the bar to the app exactly; on Windows 10 they fail and dark mode stays.
    let dark = set(DWMWA_USE_IMMERSIVE_DARK_MODE as u32, 1);
    let caption = set(DWMWA_CAPTION_COLOR as u32, CAPTION);
    set(DWMWA_TEXT_COLOR as u32, TEXT);
    dark || caption
}

#[cfg(not(windows))]
pub fn dark_frame(_hwnd: isize) -> bool {
    false
}
