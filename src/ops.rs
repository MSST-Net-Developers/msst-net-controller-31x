//! Operations composed of several IPC requests, shared by every front end
//! (Tauri, WebUI, Android).

use msst_ipc_protocol::IpcRequest;
use serde_json::{Value, json};

use crate::error::{ControllerError, Result};
use crate::ipc::client::IpcClient;

/// Delete every firewall rule (3.0.x "清空规则").
///
/// Result: `{ "deleted": n, "failed": [{ "rule_id", "message" }] }`.
///
/// Protocol 0.3 Cores clear atomically (`ClearAclRules`); with older Cores
/// rules are deleted one by one from Core's current list and partial
/// failures are reported, never hidden.
pub async fn clear_acl_rules(client: &IpcClient) -> Result<Value> {
    if client.handshake_result().await.is_some_and(|h| h.capabilities.clear_acl_rules) {
        let r = client.command(IpcRequest::ClearAclRules).await?;
        let deleted = r
            .get("deleted")
            .and_then(Value::as_u64)
            .ok_or_else(|| ControllerError::Codec("ClearAclRules: response has no `deleted` count".into()))?;
        return Ok(json!({ "deleted": deleted, "failed": [] }));
    }

    let list = client.query(IpcRequest::GetAclRules).await?;
    let ids: Vec<String> = list
        .get("rules")
        .and_then(Value::as_array)
        .ok_or_else(|| ControllerError::Codec("GetAclRules: response has no `rules` array".into()))?
        .iter()
        .filter_map(|r| r.get("id").and_then(Value::as_str).map(str::to_string))
        .collect();

    let mut deleted = 0usize;
    let mut failed = Vec::new();
    for rule_id in ids {
        match client.command(IpcRequest::DeleteAclRule { rule_id: rule_id.clone() }).await {
            Ok(_) => deleted += 1,
            // Already gone (e.g. deleted elsewhere) is the desired outcome.
            Err(ControllerError::CoreError { code, .. }) if code == "not_found" => deleted += 1,
            Err(e @ (ControllerError::NotConnected { .. } | ControllerError::ConnectionLost)) => {
                if deleted == 0 && failed.is_empty() {
                    return Err(e);
                }
                failed.push(json!({ "rule_id": rule_id, "message": e.user_message() }));
                break;
            }
            Err(e) => failed.push(json!({ "rule_id": rule_id, "message": e.user_message() })),
        }
    }
    Ok(json!({ "deleted": deleted, "failed": failed }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ipc::client::IpcClientConfig;
    use crate::ipc::mock::{FakeCore, default_responses};
    use crate::state::{StateStore, SyncState};
    use msst_ipc_protocol::IpcError;

    async fn synced(client: &IpcClient) {
        for _ in 0..200 {
            if client.state_store().snapshot().await.sync == SyncState::Synced {
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        panic!("not synced");
    }

    #[tokio::test]
    async fn atomic_clear_on_protocol_0_3() {
        let core = FakeCore::new(|req| match req {
            IpcRequest::ClearAclRules => Ok(json!({ "deleted": 3 })),
            IpcRequest::DeleteAclRule { .. } => panic!("must not delete one by one"),
            other => default_responses(other),
        });
        let client = IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), core.connector());
        synced(&client).await;
        let r = clear_acl_rules(&client).await.unwrap();
        assert_eq!(r, json!({ "deleted": 3, "failed": [] }));
        client.shutdown();
    }

    #[tokio::test]
    async fn partial_failure_is_reported() {
        let core = FakeCore::new(|req| match req {
            IpcRequest::GetAclRules => Ok(json!({ "rules": [{ "id": "a" }, { "id": "b" }, { "id": "c" }] })),
            IpcRequest::DeleteAclRule { rule_id } if rule_id == "b" => {
                Err(IpcError { code: "persist_failed".into(), message: "disk full".into() })
            }
            IpcRequest::DeleteAclRule { rule_id } if rule_id == "c" => {
                Err(IpcError { code: "not_found".into(), message: "gone".into() })
            }
            other => default_responses(other),
        });
        // Protocol 0.2 Core: no ClearAclRules.
        core.set_version(msst_ipc_protocol::version::make_version(0, 2, 0));
        let client = IpcClient::with_connector(IpcClientConfig::default(), StateStore::new(), core.connector());
        synced(&client).await;
        let r = clear_acl_rules(&client).await.unwrap();
        assert_eq!(r["deleted"], 2);
        assert_eq!(r["failed"][0]["rule_id"], "b");
        assert!(r["failed"][0]["message"].as_str().unwrap().contains("无法保存配置"));
        client.shutdown();
    }
}
