//! Tray icon on Linux: a StatusNotifierItem on the session bus (KDE Plasma,
//! and GNOME with the AppIndicator extension), its menu exported over
//! D-Bus.  The JVM's own tray is an XEmbed icon, which those desktops only
//! show through a proxy: without its menu, and ignoring clicks.

use std::sync::OnceLock;

use ksni::blocking::{Handle, TrayMethods};
use ksni::menu::StandardItem;
use ksni::{Icon, MenuItem, ToolTip};

/// What the user did with the icon.
pub enum Event {
    /// Clicked it (the SNI `Activate`).
    Activate,
    /// Chose menu entry `n`.
    Menu(usize),
}

pub struct Spec {
    pub title: String,
    pub icon: Icon,
    /// Entry labels; an empty label is a separator.
    pub menu: Vec<String>,
    pub on_event: Box<dyn Fn(Event) + Send>,
}

struct Tray(Spec);

impl ksni::Tray for Tray {
    fn id(&self) -> String {
        "msst-net-controller".into()
    }

    fn title(&self) -> String {
        self.0.title.clone()
    }

    fn icon_pixmap(&self) -> Vec<Icon> {
        vec![self.0.icon.clone()]
    }

    fn tool_tip(&self) -> ToolTip {
        ToolTip { title: self.0.title.clone(), ..Default::default() }
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        (self.0.on_event)(Event::Activate)
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        self.0
            .menu
            .iter()
            .enumerate()
            .map(|(i, label)| {
                if label.is_empty() {
                    return MenuItem::Separator;
                }
                StandardItem {
                    // The label is plain text: no access-key underscores.
                    label: label.replace('_', "__"),
                    activate: Box::new(move |t: &mut Self| (t.0.on_event)(Event::Menu(i))),
                    ..Default::default()
                }
                .into()
            })
            .collect()
    }
}

/// The running tray; kept for the life of the process.
static TRAY: OnceLock<Handle<Tray>> = OnceLock::new();

/// Register the icon with the desktop's tray.  Fails when no tray host is
/// running (`org.kde.StatusNotifierWatcher`), or if already started.
/// `on_event` runs on the tray's own thread.
pub fn start(spec: Spec) -> Result<(), String> {
    if TRAY.get().is_some() {
        return Err("tray already started".into());
    }
    let handle = Tray(spec).spawn().map_err(|e| e.to_string())?;
    TRAY.set(handle).map_err(|_| "tray already started".to_string())
}

/// ARGB32 pixels in network byte order, `size` × `size`.
pub fn icon(size: i32, argb: Vec<u8>) -> Result<Icon, String> {
    if size <= 0 || argb.len() != (size as usize) * (size as usize) * 4 {
        return Err(format!("icon: {} bytes for {size}x{size}", argb.len()));
    }
    Ok(Icon { width: size, height: size, data: argb })
}

#[cfg(test)]
mod tests {
    #[test]
    fn icon_size_is_checked() {
        assert!(super::icon(2, vec![0; 16]).is_ok());
        assert!(super::icon(2, vec![0; 15]).is_err());
        assert!(super::icon(0, vec![]).is_err());
    }
}
