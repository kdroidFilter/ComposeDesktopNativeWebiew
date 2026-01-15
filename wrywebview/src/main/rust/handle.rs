//! Window handle utilities for cross-platform WebView creation.

use wry::dpi::{LogicalPosition, LogicalSize};
use wry::raw_window_handle::{HandleError, HasWindowHandle, RawWindowHandle, WindowHandle};
use wry::Rect;

#[cfg(target_os = "linux")]
use std::os::raw::c_ulong;
#[cfg(target_os = "linux")]
use wry::raw_window_handle::XlibWindowHandle;

#[cfg(target_os = "macos")]
use wry::raw_window_handle::AppKitWindowHandle;

#[cfg(target_os = "windows")]
use std::num::NonZeroIsize;
#[cfg(target_os = "windows")]
use wry::raw_window_handle::Win32WindowHandle;

use crate::error::WebViewError;
use crate::wry_log;

/// Wrapper around a raw window handle for WebView creation.
pub struct RawWindow {
    pub raw: RawWindowHandle,
}

impl HasWindowHandle for RawWindow {
    fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
        unsafe { Ok(WindowHandle::borrow_raw(self.raw)) }
    }
}

/// Creates a `Rect` with the given position and size, ensuring minimum dimensions.
pub fn make_bounds(x: i32, y: i32, width: i32, height: i32) -> Rect {
    let width = width.max(1);
    let height = height.max(1);
    Rect {
        position: LogicalPosition::new(x, y).into(),
        size: LogicalSize::new(width, height).into(),
    }
}

/// Converts a platform-specific handle to a `RawWindowHandle`.
pub fn raw_window_handle_from(parent_handle: u64) -> Result<RawWindowHandle, WebViewError> {
    if parent_handle == 0 {
        return Err(WebViewError::InvalidWindowHandle);
    }

    #[cfg(target_os = "windows")]
    {
        let hwnd =
            NonZeroIsize::new(parent_handle as isize).ok_or(WebViewError::InvalidWindowHandle)?;
        let handle = RawWindowHandle::Win32(Win32WindowHandle::new(hwnd));
        wry_log!("[wrywebview] raw_window_handle Win32=0x{:x}", parent_handle);
        // if log_enabled() {
        //     eprintln!("[wrywebview] raw_window_handle Win32=0x{:x}", parent_handle);
        // }
        return Ok(handle);
    }

    #[cfg(target_os = "macos")]
    {
        let ns_view = crate::platform::macos::appkit_ns_view_from_handle(parent_handle)?;
        let handle = RawWindowHandle::AppKit(AppKitWindowHandle::new(ns_view));
        wry_log!(
            "[wrywebview] raw_window_handle AppKit=0x{:x} ns_view=0x{:x}",
            parent_handle,
            ns_view.as_ptr() as usize
        );
        // if log_enabled() {
        //     eprintln!(
        //         "[wrywebview] raw_window_handle AppKit=0x{:x} ns_view=0x{:x}",
        //         parent_handle,
        //         ns_view.as_ptr() as usize
        //     );
        // }
        return Ok(handle);
    }

    #[cfg(target_os = "linux")]
    {
        let handle = RawWindowHandle::Xlib(XlibWindowHandle::new(parent_handle as c_ulong));
        // if log_enabled() {
        //     eprintln!("[wrywebview] raw_window_handle Xlib=0x{:x}", parent_handle);
        // }
        wry_log!("[wrywebview] raw_window_handle Xlib=0x{:x}", parent_handle);
        return Ok(handle);
    }

    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        Err(WebViewError::UnsupportedPlatform)
    }
}
