//! Native notifications on Linux: the freedesktop notification service
//! (`org.freedesktop.Notifications` on the session bus — KDE Plasma, GNOME,
//! and other desktops), so they appear in the desktop's notification
//! centre and follow its do-not-disturb settings.

use notify_rust::{Hint, Notification, Urgency};

const APP_NAME: &str = "MSST-Net 控制器";
/// A themed icon present in Breeze and Adwaita.
const ICON: &str = "network-vpn";

/// Show a notification.  `on_activate` runs on a background thread when
/// the user clicks it (the `default` action); the thread ends when the
/// notification is closed.
pub fn show(title: &str, body: &str, on_activate: Option<Box<dyn FnOnce() + Send>>) -> Result<(), String> {
    // The body may contain names supplied by other devices: escape it where
    // the server interprets markup.
    let markup = notify_rust::get_capabilities().map(|c| c.iter().any(|c| c == "body-markup")).unwrap_or(false);
    let body = if markup { escape_markup(body) } else { body.to_string() };
    let mut n = Notification::new();
    n.appname(APP_NAME)
        .summary(title)
        .body(&body)
        .icon(ICON)
        .hint(Hint::Category("network".into()))
        .urgency(Urgency::Normal);
    if on_activate.is_some() {
        n.action("default", "打开控制器");
    }
    let handle = n.show().map_err(|e| e.to_string())?;
    if let Some(f) = on_activate {
        std::thread::Builder::new()
            .name("msst-notification".into())
            .spawn(move || {
                handle.wait_for_action(|action| {
                    if action == "default" {
                        f();
                    }
                })
            })
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn escape_markup(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

#[cfg(test)]
mod tests {
    #[test]
    fn markup_is_escaped() {
        assert_eq!(super::escape_markup("<b>Bob</b> & co"), "&lt;b&gt;Bob&lt;/b&gt; &amp; co");
    }
}
