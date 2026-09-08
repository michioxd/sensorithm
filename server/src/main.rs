slint::include_modules!();

use slint::{ModelRc, VecModel};
use std::rc::Rc;

fn main() -> Result<(), slint::PlatformError> {
    let ui = MainWindow::new()?;

    ui.set_port("4420".into());
    ui.set_phone_name("".into());
    ui.set_major_version("".into());
    ui.set_client_ver("".into());
    ui.set_server_ver("1.0.0".into());

    let adb_found = which::which("adb").is_ok();
    ui.set_adb_found(adb_found);

    let initial_states = vec![false, false, false, false, false, false];
    let sensor_model = Rc::new(VecModel::from(initial_states));
    ui.set_sensor_states(ModelRc::from(sensor_model.clone()));

    ui.on_github_clicked(|| {
        let _ = webbrowser::open("https://github.com/michioxd/sensorithm");
    });

    ui.run()
}