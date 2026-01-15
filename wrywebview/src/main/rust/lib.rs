//! Native WebView bindings using wry.
//!
//! This library provides a cross-platform WebView implementation
//! exposed through UniFFI for use from Kotlin/Swift.

mod error;
mod handle;
mod platform;
mod state;

use std::str::FromStr;
use std::sync::atomic::Ordering;
use std::sync::{Arc, OnceLock, RwLock};

use wry::cookie::time::OffsetDateTime;
use wry::cookie::{Cookie, Expiration, SameSite};
use wry::http::header::HeaderName;
use wry::http::{HeaderMap, HeaderValue};
use wry::WebViewBuilder;

pub use error::WebViewError;

use handle::{make_bounds, raw_window_handle_from, RawWindow};
use state::{get_state, register, unregister, with_webview, WebViewState};

#[cfg(target_os = "linux")]
use platform::linux::{ensure_gtk_initialized, run_on_gtk_thread};

#[cfg(target_os = "linux")]
use wry::WebViewExtUnix;

#[cfg(not(target_os = "linux"))]
use platform::run_on_main_thread;

#[cfg(target_os = "macos")]
use platform::macos::{DispatchQueue, MainThreadMarker};

// =============================================================================
// Public records/enums (UniFFI)
// =============================================================================

#[derive(Debug, Clone, uniffi::Record)]
pub struct HttpHeader {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum CookieSameSite {
    None,
    Lax,
    Strict,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct WebViewCookie {
    pub name: String,
    pub value: String,
    pub domain: Option<String>,
    pub path: Option<String>,
    /// Unix timestamp in milliseconds.
    pub expires_date_ms: Option<i64>,
    pub is_session_only: bool,
    /// Max-Age in seconds.
    pub max_age_sec: Option<i64>,
    pub same_site: Option<CookieSameSite>,
    pub is_secure: Option<bool>,
    pub is_http_only: Option<bool>,
}

fn header_map_from(headers: Vec<HttpHeader>) -> Result<HeaderMap, WebViewError> {
    let mut map = HeaderMap::new();
    for header in headers {
        let name = HeaderName::from_str(&header.name).map_err(|_| {
            WebViewError::Internal(format!("invalid header name: {}", header.name))
        })?;
        let value = HeaderValue::from_str(&header.value).map_err(|_| {
            WebViewError::Internal(format!("invalid header value for {}: {}", header.name, header.value))
        })?;
        map.insert(name, value);
    }
    Ok(map)
}

fn cookie_record_from(cookie: &Cookie<'_>) -> WebViewCookie {
    let expires_date_ms = cookie
        .expires()
        .and_then(Expiration::datetime)
        .map(|dt| dt.unix_timestamp() * 1000 + (dt.nanosecond() as i64 / 1_000_000));

    let is_session_only = matches!(cookie.expires(), Some(Expiration::Session));

    WebViewCookie {
        name: cookie.name().to_string(),
        value: cookie.value().to_string(),
        domain: cookie.domain().map(ToString::to_string),
        path: cookie.path().map(ToString::to_string),
        expires_date_ms,
        is_session_only,
        max_age_sec: cookie.max_age().map(|d| d.whole_seconds()),
        same_site: cookie.same_site().map(|s| match s {
            SameSite::None => CookieSameSite::None,
            SameSite::Lax => CookieSameSite::Lax,
            SameSite::Strict => CookieSameSite::Strict,
        }),
        is_secure: cookie.secure(),
        is_http_only: cookie.http_only(),
    }
}

fn cookie_from_record(cookie: WebViewCookie) -> Result<Cookie<'static>, WebViewError> {
    let mut builder = Cookie::build((cookie.name, cookie.value));

    if let Some(domain) = cookie.domain {
        builder = builder.domain(domain);
    }
    if let Some(path) = cookie.path {
        builder = builder.path(path);
    }
    if let Some(expires_ms) = cookie.expires_date_ms {
        let dt = OffsetDateTime::from_unix_timestamp_nanos((expires_ms as i128) * 1_000_000)
            .map_err(|_| WebViewError::Internal("invalid expires_date_ms".to_string()))?;
        builder = builder.expires(dt);
    } else if cookie.is_session_only {
        builder = builder.expires(None::<OffsetDateTime>);
    }

    if let Some(max_age) = cookie.max_age_sec {
        builder = builder.max_age(wry::cookie::time::Duration::seconds(max_age));
    }
    if let Some(is_secure) = cookie.is_secure {
        builder = builder.secure(is_secure);
    }
    if let Some(is_http_only) = cookie.is_http_only {
        builder = builder.http_only(is_http_only);
    }
    if let Some(same_site) = cookie.same_site {
        let mapped = match same_site {
            CookieSameSite::None => SameSite::None,
            CookieSameSite::Lax => SameSite::Lax,
            CookieSameSite::Strict => SameSite::Strict,
        };
        builder = builder.same_site(mapped);
    }

    Ok(builder.build())
}

use std::sync::atomic::AtomicBool;

static LOG_ENABLED: AtomicBool = AtomicBool::new(false);

pub(crate) fn log_enabled() -> bool {
    LOG_ENABLED.load(Ordering::Relaxed)
}

#[uniffi::export]
pub fn set_log_enabled(enabled: bool) {
    LOG_ENABLED.store(enabled, Ordering::Relaxed);
}

#[uniffi::export(callback_interface)]
pub trait NativeLogger: Send + Sync {
    fn handle_log(&self, data: String);
}

static GLOBAL_LOGGER: OnceLock<RwLock<Option<Box<dyn NativeLogger>>>> = OnceLock::new();

fn get_logger_registry() -> &'static RwLock<Option<Box<dyn NativeLogger>>> {
    GLOBAL_LOGGER.get_or_init(|| RwLock::new(None))
}

#[uniffi::export]
pub fn set_native_logger(logger: Box<dyn NativeLogger>) {
    let mut lock = get_logger_registry().write().unwrap();
    *lock = Some(logger);
}

#[macro_export]
macro_rules! wry_log {
    ($($arg:tt)*) => {
        $crate::do_internal_log(format_args!($($arg)*));
    };
}

#[doc(hidden)]
pub fn do_internal_log(args: std::fmt::Arguments) {
    if !log_enabled() {
        return;
    }
    let log_string = args.to_string();

    if let Ok(lock) = crate::get_logger_registry().read() {
        if let Some(ref logger) = *lock {
            logger.handle_log(log_string);
            return;
        }
    }
    eprintln!("{}", log_string);
}

// ============================================================================
// WebView Creation
// ============================================================================

#[uniffi::export(callback_interface)]
pub trait NavigationHandler: Send + Sync {
    /// Return true to allow navigation, false to cancel.
    fn handle_navigation(&self, url: String) -> bool;
}

fn create_webview_inner(
    parent_handle: u64,
    width: i32,
    height: i32,
    url: String,
    user_agent: Option<String>,
    nav_handler: Option<Box<dyn NavigationHandler>>,
) -> Result<u64, WebViewError> {
    let user_agent =
        user_agent.and_then(|ua| {
            let trimmed = ua.trim().to_string();
            if trimmed.is_empty() { None } else { Some(trimmed) }
        });

    wry_log!(
        "[wrywebview] create_webview handle=0x{:x} size={}x{} url={} user_agent={}",
        parent_handle,
        width,
        height,
        url,
        user_agent.as_deref().unwrap_or("<default>")
    );

    let raw = raw_window_handle_from(parent_handle)?;
    let window = RawWindow { raw };

    #[cfg(target_os = "linux")]
    ensure_gtk_initialized()?;

    let state = Arc::new(WebViewState::new(url.clone()));
    let state_for_nav = Arc::clone(&state);
    let state_for_load = Arc::clone(&state);
    let state_for_title = Arc::clone(&state);
    let state_for_ipc = Arc::clone(&state);

    let mut builder = WebViewBuilder::new()
        .with_url(&url)
        .with_bounds(make_bounds(0, 0, width, height));

    if let Some(ua) = user_agent {
        builder = builder.with_user_agent(ua);
    }

    let webview = builder
        .with_navigation_handler(move |new_url| {
            if let Some(handler) = &nav_handler {
                return handler.handle_navigation(new_url.to_string());
            }

            wry_log!("[wrywebview] navigation_handler url={}", new_url);
            state_for_nav.is_loading.store(true, Ordering::SeqCst);
            if let Err(e) = state_for_nav.update_current_url(new_url.clone()) {
                wry_log!("[wrywebview] navigation_handler state update failed: {}", e);
            }

            true
        })
        .with_on_page_load_handler(move |event, url| {
            match event {
                wry::PageLoadEvent::Started => {
                    wry_log!("[wrywebview] page_load_handler event=Started url={}", url);
                    state_for_load.is_loading.store(true, Ordering::SeqCst);
                }
                wry::PageLoadEvent::Finished => {
                    wry_log!("[wrywebview] page_load_handler event=Finished url={}", url);
                    state_for_load.is_loading.store(false, Ordering::SeqCst);
                    if let Err(e) = state_for_load.update_current_url(url.clone()) {
                        wry_log!("[wrywebview] page_load_handler state update failed: {}", e);
                    }
                }
            }
        })
        .with_document_title_changed_handler(move |title| {
            wry_log!("[wrywebview] title_changed title={}", title);
            if let Err(e) = state_for_title.update_page_title(title) {
                wry_log!("[wrywebview] title_changed state update failed: {}", e);
            }
        })
        .with_ipc_handler(move |request| {
            let url = request.uri().to_string();
            let message = request.into_body();
            wry_log!("[wrywebview] ipc url={} body_len={}", url, message.len());
            if let Err(e) = state_for_ipc.push_ipc_message(message) {
                wry_log!("[wrywebview] ipc queue push failed: {}", e);
            }
        })
        .build_as_child(&window)?;

    // On Linux, set up focus handling for the GTK widget
    #[cfg(target_os = "linux")]
    {
        use gdkx11::glib::translate::ToGlibPtr;
        use gdkx11::glib::Cast;
        use gdkx11::X11Display;
        use gtk::prelude::WidgetExt;

        let gtk_widget = webview.webview();
        gtk_widget.set_can_focus(true);

        // Connect to button-press-event to grab focus when clicked using X11
        gtk_widget.connect_button_press_event(|widget, _event| {
            wry_log!("[wrywebview] button_press_event -> grab_focus");

            // Use X11 focus directly for proper keyboard input
            if let Some(gdk_window) = widget.window() {
                if let Some(display) = gdk::Display::default() {
                    if let Ok(x11_display) = display.downcast::<X11Display>() {
                        unsafe {
                            let gdk_window_ptr: *mut gdk::ffi::GdkWindow = gdk_window.to_glib_none().0;
                            let xid = gdkx11::ffi::gdk_x11_window_get_xid(
                                gdk_window_ptr as *mut gdkx11::ffi::GdkX11Window,
                            );

                            if xid != 0 {
                                let x11_display_ptr: *mut gdkx11::ffi::GdkX11Display = x11_display.to_glib_none().0;
                                let x_display = gdkx11::ffi::gdk_x11_display_get_xdisplay(x11_display_ptr);

                                if !x_display.is_null() {
                                    x11::xlib::XSetInputFocus(
                                        x_display as *mut x11::xlib::Display,
                                        xid,
                                        x11::xlib::RevertToParent,
                                        x11::xlib::CurrentTime,
                                    );
                                    wry_log!("[wrywebview] button_press XSetInputFocus xid=0x{:x}", xid);
                                }
                            }
                        }
                    }
                }
            }

            widget.grab_focus();
            gtk::glib::Propagation::Proceed
        });
        wry_log!("[wrywebview] gtk focus handling configured with X11 support");
    }

    let id = register(webview, state)?;
    wry_log!("[wrywebview] create_webview success id={}", id);
    Ok(id)
}

#[uniffi::export]
pub fn create_webview(
    parent_handle: u64,
    width: i32,
    height: i32,
    url: String,
    nav_handler: Option<Box<dyn NavigationHandler>>
) -> Result<u64, WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || {
            create_webview_inner(parent_handle, width, height, url, None, nav_handler)
        });
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || create_webview_inner(parent_handle, width, height, url, None, nav_handler))
}

#[uniffi::export]
pub fn create_webview_with_user_agent(
    parent_handle: u64,
    width: i32,
    height: i32,
    url: String,
    user_agent: Option<String>,
    nav_handler: Option<Box<dyn NavigationHandler>>
) -> Result<u64, WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || {
            create_webview_inner(parent_handle, width, height, url, user_agent, nav_handler)
        });
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || create_webview_inner(parent_handle, width, height, url, user_agent,nav_handler))
}

// ============================================================================
// Bounds Management
// ============================================================================

fn set_bounds_inner(id: u64, x: i32, y: i32, width: i32, height: i32) -> Result<(), WebViewError> {
    wry_log!(
        "[wrywebview] set_bounds id={} pos=({}, {}) size={}x{}",
        id, x, y, width, height
    );
    let bounds = make_bounds(x, y, width, height);
    with_webview(id, |webview| webview.set_bounds(bounds).map_err(WebViewError::from))
}

#[uniffi::export]
pub fn set_bounds(id: u64, x: i32, y: i32, width: i32, height: i32) -> Result<(), WebViewError> {
    #[cfg(target_os = "macos")]
    {
        if MainThreadMarker::new().is_some() {
            return set_bounds_inner(id, x, y, width, height);
        }
        DispatchQueue::main().exec_async(move || {
            let _ = set_bounds_inner(id, x, y, width, height);
        });
        return Ok(());
    }

    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || set_bounds_inner(id, x, y, width, height));
    }

    #[cfg(target_os = "windows")]
    {
        run_on_main_thread(move || set_bounds_inner(id, x, y, width, height))
    }
}

// ============================================================================
// Navigation
// ============================================================================

fn load_url_inner(id: u64, url: String) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] load_url id={} url={}", id, url);
    if let Ok(state) = get_state(id) {
        state.is_loading.store(true, Ordering::SeqCst);
    }
    with_webview(id, |webview| webview.load_url(&url).map_err(WebViewError::from))
}

#[uniffi::export]
pub fn load_url(id: u64, url: String) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || load_url_inner(id, url));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || load_url_inner(id, url))
}

fn load_url_with_headers_inner(
    id: u64,
    url: String,
    headers: Vec<HttpHeader>,
) -> Result<(), WebViewError> {
    wry_log!(
        "[wrywebview] load_url_with_headers id={} url={} headers={}",
        id,
        url,
        headers.len()
    );
    if let Ok(state) = get_state(id) {
        state.is_loading.store(true, Ordering::SeqCst);
    }
    let header_map = header_map_from(headers)?;
    with_webview(id, |webview| {
        webview
            .load_url_with_headers(&url, header_map)
            .map_err(WebViewError::from)
    })
}

#[uniffi::export]
pub fn load_url_with_headers(
    id: u64,
    url: String,
    headers: Vec<HttpHeader>,
) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || load_url_with_headers_inner(id, url, headers));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || load_url_with_headers_inner(id, url, headers))
}

fn load_html_inner(id: u64, html: String) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] load_html id={} bytes={}", id, html.len());
    if let Ok(state) = get_state(id) {
        state.is_loading.store(true, Ordering::SeqCst);
    }
    with_webview(id, |webview| webview.load_html(&html).map_err(WebViewError::from))
}

#[uniffi::export]
pub fn load_html(id: u64, html: String) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || load_html_inner(id, html));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || load_html_inner(id, html))
}

fn stop_loading_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] stop_loading id={}", id);
    if let Ok(state) = get_state(id) {
        state.is_loading.store(false, Ordering::SeqCst);
    }
    with_webview(id, |webview| {
        webview
            .evaluate_script("window.stop && window.stop();")
            .map_err(WebViewError::from)
    })
}

#[uniffi::export]
pub fn stop_loading(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || stop_loading_inner(id));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || stop_loading_inner(id))
}

#[uniffi::export(callback_interface)]
pub trait JavaScriptCallback: Send + Sync {
    fn on_result(&self, result: String);
}

fn evaluate_javascript_inner(
    id: u64,
    script: String,
    callback: Box<dyn JavaScriptCallback>,
) -> Result<(), WebViewError> {
    with_webview(id, |webview| {
        let _ = webview.evaluate_script_with_callback(&script, move |result| {
            callback.on_result(result);
        });
        Ok(())
    })
}

#[uniffi::export]
pub fn evaluate_javascript(
    id: u64,
    script: String,
    callback: Box<dyn JavaScriptCallback>,
) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || evaluate_javascript_inner(id, script, callback));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || evaluate_javascript_inner(id, script, callback))
}

fn go_back_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] go_back id={}", id);
    if let Ok(state) = get_state(id) {
        state.is_loading.store(true, Ordering::SeqCst);
    }
    with_webview(id, |webview| {
        webview
            .evaluate_script("window.history.back()")
            .map_err(WebViewError::from)
    })
}

#[uniffi::export]
pub fn go_back(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || go_back_inner(id));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || go_back_inner(id))
}

fn go_forward_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] go_forward id={}", id);
    if let Ok(state) = get_state(id) {
        state.is_loading.store(true, Ordering::SeqCst);
    }
    with_webview(id, |webview| {
        webview
            .evaluate_script("window.history.forward()")
            .map_err(WebViewError::from)
    })
}

#[uniffi::export]
pub fn go_forward(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || go_forward_inner(id));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || go_forward_inner(id))
}

fn reload_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] reload id={}", id);
    if let Ok(state) = get_state(id) {
        state.is_loading.store(true, Ordering::SeqCst);
    }
    with_webview(id, |webview| {
        webview
            .evaluate_script("window.location.reload()")
            .map_err(WebViewError::from)
    })
}

#[uniffi::export]
pub fn reload(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || reload_inner(id));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || reload_inner(id))
}

// ============================================================================
// Focus
// ============================================================================

fn focus_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] focus id={}", id);
    with_webview(id, |webview| {
        // On Linux, we need to use X11 focus directly since the GTK widget
        // is embedded in a foreign (AWT/Swing) window hierarchy
        #[cfg(target_os = "linux")]
        {
            use gdkx11::glib::translate::ToGlibPtr;
            use gdkx11::glib::Cast;
            use gdkx11::X11Display;
            use gtk::prelude::WidgetExt;

            let gtk_widget = webview.webview();
            gtk_widget.set_can_focus(true);

            // First, ensure the widget is realized and has a window
            if !gtk_widget.is_realized() {
                gtk_widget.realize();
            }

            // Get the GDK window and use X11 to set focus
            if let Some(gdk_window) = gtk_widget.window() {
                // Get the X11 display from GDK
                if let Some(display) = gdk::Display::default() {
                    if let Ok(x11_display) = display.downcast::<X11Display>() {
                        unsafe {
                            // Get the X11 window ID (XID) from the GDK window
                            let gdk_window_ptr: *mut gdk::ffi::GdkWindow = gdk_window.to_glib_none().0;
                            let xid = gdkx11::ffi::gdk_x11_window_get_xid(
                                gdk_window_ptr as *mut gdkx11::ffi::GdkX11Window,
                            );

                            if xid != 0 {
                                // Get the raw X11 display pointer
                                let x11_display_ptr: *mut gdkx11::ffi::GdkX11Display = x11_display.to_glib_none().0;
                                let x_display = gdkx11::ffi::gdk_x11_display_get_xdisplay(x11_display_ptr);

                                if !x_display.is_null() {
                                    // XSetInputFocus: RevertToParent = 2, CurrentTime = 0
                                    x11::xlib::XSetInputFocus(
                                        x_display as *mut x11::xlib::Display,
                                        xid,
                                        x11::xlib::RevertToParent,
                                        x11::xlib::CurrentTime,
                                    );
                                    wry_log!("[wrywebview] XSetInputFocus xid=0x{:x}", xid);
                                }
                            }
                        }
                    }
                }
            }

            // Also call GTK grab_focus as a fallback
            gtk_widget.grab_focus();
            wry_log!("[wrywebview] gtk grab_focus called");
        }

        webview
            .evaluate_script("document.documentElement.focus(); window.focus();")
            .map_err(WebViewError::from)
    })
}

#[uniffi::export]
pub fn focus(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || focus_inner(id));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || focus_inner(id))
}

// ============================================================================
// State Queries
// ============================================================================

#[uniffi::export]
pub fn get_url(id: u64) -> Result<String, WebViewError> {
    let state = get_state(id)?;
    let url = state
        .current_url
        .lock()
        .map_err(|_| WebViewError::Internal("url lock poisoned".to_string()))?;
    Ok(url.clone())
}

#[uniffi::export]
pub fn is_loading(id: u64) -> Result<bool, WebViewError> {
    let state = get_state(id)?;
    Ok(state.is_loading.load(Ordering::SeqCst))
}

#[uniffi::export]
pub fn get_title(id: u64) -> Result<String, WebViewError> {
    let state = get_state(id)?;
    let title = state
        .page_title
        .lock()
        .map_err(|_| WebViewError::Internal("title lock poisoned".to_string()))?;
    Ok(title.clone())
}

#[uniffi::export]
pub fn can_go_back(id: u64) -> Result<bool, WebViewError> {
    let state = get_state(id)?;
    state.can_go_back()
}

#[uniffi::export]
pub fn can_go_forward(id: u64) -> Result<bool, WebViewError> {
    let state = get_state(id)?;
    state.can_go_forward()
}

#[uniffi::export]
pub fn drain_ipc_messages(id: u64) -> Result<Vec<String>, WebViewError> {
    let state = get_state(id)?;
    state.drain_ipc_messages()
}

// ============================================================================
// Cookies
// ============================================================================

fn get_cookies_for_url_inner(id: u64, url: String) -> Result<Vec<WebViewCookie>, WebViewError> {
    wry_log!("[wrywebview] get_cookies_for_url id={} url={}", id, url);
    with_webview(id, |webview| {
        let cookies = webview.cookies_for_url(&url).map_err(WebViewError::from)?;
        Ok(cookies.iter().map(cookie_record_from).collect())
    })
}

#[uniffi::export]
pub fn get_cookies_for_url(id: u64, url: String) -> Result<Vec<WebViewCookie>, WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || get_cookies_for_url_inner(id, url));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || get_cookies_for_url_inner(id, url))
}

fn clear_cookies_for_url_inner(id: u64, url: String) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] clear_cookies_for_url id={} url={}", id, url);
    with_webview(id, |webview| {
        let cookies = webview.cookies_for_url(&url).map_err(WebViewError::from)?;
        for cookie in cookies {
            webview
                .delete_cookie(&cookie)
                .map_err(WebViewError::from)?;
        }
        Ok(())
    })
}

#[uniffi::export]
pub fn clear_cookies_for_url(id: u64, url: String) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || clear_cookies_for_url_inner(id, url));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || clear_cookies_for_url_inner(id, url))
}

fn clear_all_cookies_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] clear_all_cookies id={}", id);
    with_webview(id, |webview| {
        let cookies = webview.cookies().map_err(WebViewError::from)?;
        for cookie in cookies {
            webview
                .delete_cookie(&cookie)
                .map_err(WebViewError::from)?;
        }
        Ok(())
    })
}

#[uniffi::export]
pub fn clear_all_cookies(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || clear_all_cookies_inner(id));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || clear_all_cookies_inner(id))
}

fn set_cookie_inner(id: u64, cookie: WebViewCookie) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] set_cookie id={} name={}", id, &cookie.name);
    let native = cookie_from_record(cookie)?;
    with_webview(id, |webview| webview.set_cookie(&native).map_err(WebViewError::from))
}

#[uniffi::export]
pub fn set_cookie(id: u64, cookie: WebViewCookie) -> Result<(), WebViewError> {
    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || set_cookie_inner(id, cookie));
    }

    #[cfg(not(target_os = "linux"))]
    run_on_main_thread(move || set_cookie_inner(id, cookie))
}

// ============================================================================
// Destruction
// ============================================================================

fn destroy_webview_inner(id: u64) -> Result<(), WebViewError> {
    wry_log!("[wrywebview] destroy_webview id={}", id);
    unregister(id)
}

#[uniffi::export]
pub fn destroy_webview(id: u64) -> Result<(), WebViewError> {
    #[cfg(target_os = "macos")]
    {
        if MainThreadMarker::new().is_some() {
            return destroy_webview_inner(id);
        }
        DispatchQueue::main().exec_async(move || {
            let _ = destroy_webview_inner(id);
        });
        return Ok(());
    }

    #[cfg(target_os = "linux")]
    {
        return run_on_gtk_thread(move || destroy_webview_inner(id));
    }

    #[cfg(any(target_os = "windows", not(any(target_os = "linux", target_os = "macos"))))]
    run_on_main_thread(move || destroy_webview_inner(id))
}

// ============================================================================
// Event Pumps
// ============================================================================

#[uniffi::export]
pub fn pump_gtk_events() {
    #[cfg(target_os = "linux")]
    {
        // Events are pumped continuously on the dedicated GTK thread.
    }
}

#[uniffi::export]
pub fn pump_windows_events() {
    #[cfg(target_os = "windows")]
    {
        platform::windows::pump_events();
    }
}

uniffi::setup_scaffolding!();
