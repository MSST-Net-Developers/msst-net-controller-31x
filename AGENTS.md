# MSST-Net Controller — AGENTS.md

## 1. Project Overview

Controller is the user-facing component of MSST-Net 3.x.

MSST-Net is a private virtual networking system primarily intended for internal use by the MSST team. Its main use cases include:

- Private virtual LAN networking
- Minecraft and other game networking
- Access to private services
- Long-running connections between trusted devices
- Room-based networking and interactive access authorization

MSST-Net 3.x separates the privileged Core from the unprivileged Controller. They communicate through IPC.

The Controller provides the graphical user interface and translates user operations into IPC requests. It presents the state reported by Core and must not independently implement networking decisions.

The primary objective for MSST-Net 3.1.x is to improve reliability, usability, diagnosability, and maintainability without unnecessary feature expansion.

## 2. Mission and Scope

Your responsibility is the Controller application and its integration with Core.

Primary responsibilities:

- GUI layout and interaction
- Application-level presentation state
- IPC client integration
- Initial state synchronization
- Event subscription and processing
- Connection status presentation
- Node and room management interfaces
- ACL configuration interfaces
- Interactive authorization dialogs
- Error reporting and diagnostics
- Application lifecycle and reconnection handling

You may propose changes to the IPC protocol or Core API when necessary, but do not independently change their semantics.

The Controller is not responsible for:

- Network path selection
- NAT traversal
- WireGuard configuration
- Routing-table manipulation
- Firewall enforcement
- ACL evaluation
- Authoritative node or connection state
- Network recovery decisions
- Authentication policy enforcement

These responsibilities belong to Core or the relevant backend service.

## 3. Architectural Principles

### 3.1 Core owns authoritative state

Core is the single source of truth for networking state.

Controller may maintain a local presentation model or cache, but it must not become an independent authority.

For example, Controller may display:

- Node connection state
- Selected path
- Current ACL rules
- Room membership
- Pending access requests
- Network diagnostics

These values must originate from Core or another explicitly designated authoritative service.

Do not infer that a node is connected merely because a connection command was submitted successfully.

### 3.2 Controller is an IPC client

The intended data flow is:

User interaction
-> Controller application logic
-> IPC request
-> Core decision and execution
-> IPC response and/or event
-> Controller state update
-> UI rendering

Controller must not bypass IPC to perform privileged operations.

Do not directly manipulate:

- Network interfaces
- WireGuard peers
- Routing tables
- System firewall rules
- Network sockets owned by Core
- Core's internal state
- Core's ACL engine

If a requested feature requires a new Core capability, identify the missing API and propose the smallest required extension.

### 3.3 Separate presentation state from domain state

Presentation state includes:

- Current navigation destination
- Expanded or collapsed sections
- Dialog visibility
- Form input
- Search filters
- Sorting and selection
- Loading indicators

Domain state includes:

- Node identity
- Actual connection status
- Network membership
- ACL decisions
- Room membership
- Authorization request status

Keep these categories distinct.

A local Boolean such as `isConnected` must not replace the authoritative connection state received from Core.

Prefer explicit UI states such as:

- Loading
- Ready
- Submitting
- Reconnecting
- Error

Avoid introducing a second, independent networking state machine inside the GUI.

### 3.4 Preserve the privilege boundary

Controller should run without unnecessary elevated privileges.

Do not request administrator or root privileges simply to simplify GUI implementation.

If an operation requires privileges, request it through the established Core IPC interface.

Do not weaken IPC authentication or authorization to make the GUI easier to implement.

## 4. Technology and Repository Conventions

Before making changes:

1. Inspect the existing repository and build configuration.
2. Identify the actual Controller framework and supported platforms.
3. Identify existing UI architecture, navigation, dependency injection, and state-management conventions.
4. Identify the existing IPC client and message models.
5. Follow established conventions unless there is a concrete reason to change them.

If the repository uses Kotlin Multiplatform and Jetpack Compose, prefer the existing Compose and KMP architecture rather than introducing a second UI framework.

Keep platform-specific functionality behind appropriate platform abstractions.

Do not introduce a new dependency, architectural framework, or state-management library merely because it is fashionable.

Do not assume that all platforms support identical IPC transports. For example, Linux may use Unix domain sockets, while Windows may use named pipes. Follow the existing transport abstraction and platform implementation.

## 5. IPC Integration

### 5.1 Use the existing IPC contract

Before implementing a feature, inspect the existing IPC protocol and determine:

- Which request is required?
- Which response is returned?
- Which events report subsequent changes?
- Which errors can occur?
- Which component owns the authoritative data?
- What happens if the connection is interrupted?

Reuse existing protocol types and clients.

Do not duplicate protocol definitions inside GUI components.

If the protocol is missing a necessary operation, document the gap rather than inventing an undocumented message.

### 5.2 Commands are not final outcomes

A successful command response may mean only that Core accepted a request.

For example:

ConnectNode
-> Request accepted
-> NodeConnecting event
-> Connection attempt
-> NodeConnected event

The UI must distinguish between:

- Request submitted
- Request accepted
- Operation in progress
- Operation completed
- Operation failed

Never display success merely because an IPC request returned without a transport error.

### 5.3 Handle IPC failures explicitly

The Controller must handle:

- Core not running
- Initial connection failure
- IPC disconnection
- Request timeout
- Request rejection
- Malformed or incompatible responses
- Core restart
- Controller reconnection
- Unexpected event-stream termination

Failures should produce useful feedback.

Do not silently swallow exceptions, endlessly retry requests, or leave controls permanently loading.

Use bounded retry with backoff where appropriate. Respect existing Core and IPC retry semantics rather than introducing competing retry loops.

### 5.4 Snapshot and event synchronization

If the IPC protocol supports an initial snapshot and event stream, follow the established synchronization contract.

The Controller should be able to reconstruct its view after:

- Application startup
- IPC reconnection
- Core restart
- Event-stream interruption

Do not assume that the Controller received every event while disconnected.

If the protocol provides event sequence numbers, revisions, or snapshot cursors, use them as specified.

Do not invent ordering guarantees that the protocol does not provide.

When event history cannot be recovered reliably, request a fresh snapshot if the API permits it.

### 5.5 Avoid stale asynchronous results

Multiple requests may complete out of order.

For example, a user selects node A and quickly switches to node B. A delayed response for A must not overwrite B's current detail view.

Use request identity, cancellation, selection checks, or generation tokens where appropriate.

Cancel obsolete work when practical, but do not assume cancellation can undo a command already accepted by Core.

## 6. UI State Management

Use the state-management approach already established by the project.

Keep rendering declarative and state-driven.

Prefer the following structure where appropriate:

UI
-> User action
-> ViewModel / presentation logic
-> IPC client
-> Core
-> State update
-> UI rendering

Do not place complex IPC, domain, or networking logic directly inside composable UI functions.

Avoid:

- Duplicated state across screens
- Hidden global mutable state
- Unstructured background tasks
- Blocking the main thread
- Repeated IPC calls triggered by recomposition
- UI components independently subscribing to the same global event stream
- Collectors that survive beyond their intended lifecycle

Use structured concurrency and lifecycle-aware collection where supported by the existing framework.

Keep the IPC client lifecycle separate from individual screens so navigating between screens does not unnecessarily reconnect to Core.

## 7. UI Design Principles

### 7.1 Optimize for internal users

The primary audience is the MSST team.

Prioritize:

- Clear operation semantics
- Low configuration overhead
- Predictable behavior
- Useful error messages
- Easy diagnosis
- Consistency across screens

Do not introduce complex onboarding, administration dashboards, or extensive customization unless there is a demonstrated internal requirement.

### 7.2 Prefer user-facing terminology

Avoid exposing implementation terminology unless it is useful for diagnostics.

Prefer:

- Device
- User
- Room
- Service
- Allow access
- Deny access
- Connection status
- Network diagnostics

Avoid requiring ordinary users to understand:

- UUID
- Internal node identifiers
- State machine transitions
- IPC request types
- Internal protocol fields

Identifiers may be shown in advanced diagnostics or copied when needed.

### 7.3 Show actual status

A status indicator must reflect authoritative information.

For example:

- Connected
- Connecting
- Disconnected
- Reconnecting
- Degraded
- Unknown

Only show states supported by Core's actual data model.

Do not invent status categories or infer a successful connection from the presence of a node in the node list.

Distinguish an unknown state from a known disconnected state.

### 7.4 Loading, empty, and error states

Important screens should account for:

- Loading
- Empty results
- Normal operation
- Partial data
- Request in progress
- Recoverable error
- IPC unavailable

Do not leave blank panels without explanations when data is unavailable.

Error messages should explain what failed and, where possible, what the user can do next.

Do not expose raw stack traces or sensitive implementation details in ordinary UI. Preserve useful diagnostic information in appropriate logs.

### 7.5 Avoid unnecessary confirmation dialogs

Use confirmation dialogs for destructive or consequential actions, not every ordinary interaction.

For example, deleting a persistent rule may warrant confirmation. Opening a node detail page generally does not.

Do not implement fake controls, placeholder actions that appear functional, or buttons that silently do nothing.

If a capability is not implemented, make its unavailable status clear.

## 8. Node and Connection Interfaces

The Controller may present nodes, connections, endpoints, and paths, but it must not independently select or establish network paths.

For node operations:

1. Validate user input.
2. Submit the corresponding IPC command.
3. Display the request's actual status.
4. Consume authoritative state updates.
5. Present failures clearly.

Do not equate these concepts:

- Node discovered
- Node registered
- Node reachable
- Transport established
- Virtual network connected
- Application service reachable

They represent different conditions.

Use only distinctions supported by Core's state model.

## 9. ACL Configuration Interface

MSST-Net currently supports inbound ACL rules.

A rule may contain:

- Multiple source devices or users
- Multiple destination protocol/port groups
- Allow or deny action
- Device-level or user-level scope

Users may select predefined service templates or enter custom protocol/port groups.

### 9.1 Present ACL rules in understandable language

Prefer expressing a rule as:

Who
-> Can or cannot
-> Access which services

For example:

"Allow Alice's computer to access Minecraft Java on this device."

Show protocol and port details when useful, especially for custom rules and diagnostics.

### 9.2 Preserve identity semantics

Persistent identities use UUIDs.

Mnemonics and display names are for presentation and identity resolution; they must not be treated as persistent identity.

When creating or editing a rule:

- Use the canonical identity supplied by the authoritative API.
- Display understandable names.
- Avoid silently substituting a different identity when a name changes.
- Handle deleted, unavailable, or unresolved identities explicitly.

Do not resolve or invent UUIDs locally.

### 9.3 Do not reimplement ACL evaluation

Controller submits and displays ACL configuration.

Core evaluates ACL rules and determines whether traffic is allowed.

The UI may explain a decision using structured diagnostic information from Core, but it must not implement a competing policy engine.

The current intended precedence is:

1. Device-level deny
2. Device-level allow
3. User-level deny
4. User-level allow
5. Default deny for unmatched traffic

The project also specifies that more specific rules take precedence over broader rules. The exact interaction between specificity and action precedence must follow the formally agreed Core implementation.

Do not invent a different precedence order in the GUI.

If Core does not expose enough information to explain a decision, identify the missing diagnostic data and propose an API extension.

### 9.4 Make rule coverage visible

Because one rule may contain multiple sources and multiple service groups, the interface should make the rule's effective coverage understandable.

Where appropriate, show:

- Number of sources
- Number of service groups
- Rule scope
- Action
- Enabled status, if supported

Avoid expanding every combination into a long list unless the user requests detailed inspection.

## 10. Room Mode and Interactive Authorization

Room mode is intended primarily for friends and game sessions.

It supports interactive authorization and game discovery mechanisms.

The Controller is responsible for presenting access requests and collecting the user's decision.

Core remains responsible for evaluating requests and enforcing the resulting policy.

For an incoming authorization request, present the available information supplied by Core, such as:

- Requesting user
- Requesting device
- Requested service
- Relevant room or context
- Expiration or remaining time, if supplied

Do not invent identity information or imply that the requester has already been authenticated if Core has not confirmed it.

When the user chooses an action:

1. Submit the corresponding command.
2. Display the result reported by Core.
3. Handle expired, duplicate, rejected, or already-completed requests.
4. Do not independently assume the access grant has taken effect.

Do not confuse service discovery with access authorization. Discovering a service does not mean the requester has permission to use it.

## 11. Diagnostics and Explainability

Diagnostics are a first-class part of the Controller, not an afterthought.

Where Core provides the necessary information, the UI should help users determine:

- Whether Core is running
- Whether IPC is connected
- Whether a node is known
- Whether the node is reachable
- Whether a connection is established
- Which path is being used
- Whether a request failed
- Whether ACL denied access
- Why an operation failed
- Whether recovery is in progress

Prefer structured diagnostic information over parsing human-readable log strings.

Do not claim a specific root cause when the available evidence only identifies a symptom.

If a failure cannot be diagnosed with existing APIs, document the missing information instead of fabricating an explanation.

## 12. Security and Privacy

The Controller must not expose secrets unnecessarily.

Requirements:

- Do not log private keys, authentication tokens, session credentials, or other secrets.
- Do not place secrets in user-visible error messages.
- Do not persist sensitive values without an explicit requirement.
- Do not weaken IPC security for convenience.
- Treat IPC responses and externally supplied metadata as untrusted input.
- Validate user-entered protocol and port values before submission.
- Use safe rendering for user-controlled display names and metadata.
- Avoid sending diagnostic information to external services unless explicitly designed and authorized.

Do not introduce telemetry, analytics, or external dependencies that transmit user information without explicit project approval.

## 13. Compatibility and Platform Behavior

Preserve the existing supported platform behavior.

Do not assume that platform-specific features are available everywhere.

For platform-specific IPC or operating-system integration:

- Keep implementation details behind existing abstractions.
- Follow repository conventions.
- Report unsupported capabilities explicitly.
- Avoid platform checks scattered throughout unrelated UI code.

Do not change the IPC schema, persistence format, or Core API without documenting compatibility implications.

## 14. Testing Requirements

Add or update tests for behavior introduced by your changes.

Prioritize tests for:

- Presentation-state transitions
- IPC request and response handling
- Loading and error states
- Reconnection and resynchronization
- Out-of-order asynchronous responses
- Duplicate or stale events
- ACL form validation
- ACL identity selection
- Authorization request handling
- UI state after rejected commands

Where feasible, test presentation logic using a fake IPC client instead of requiring a running Core or real network interfaces.

Integration tests should verify the actual IPC contract where practical.

Do not claim tests passed unless they were actually run.

If tests cannot run in the current environment, explain why and identify what remains unverified.

## 15. Development Workflow

Before modifying code:

1. Inspect the existing Controller structure.
2. Identify the UI framework and state-management approach.
3. Locate IPC client abstractions and message models.
4. Identify the current screen and navigation structure.
5. Identify relevant tests.
6. Understand the existing behavior before changing it.

For significant changes:

1. Explain the problem.
2. Identify the affected components.
3. Propose the smallest coherent solution.
4. Implement the change.
5. Run relevant tests and builds.
6. Review the diff for unintended changes.
7. Report remaining risks.

Prefer incremental changes over large-scale rewrites.

Do not reformat or restructure unrelated files merely to make the code look cleaner.

Do not add abstractions unless they address a real problem or a clearly established architectural requirement.

## 16. Non-Goals

Unless explicitly requested, do not:

- Redesign the entire GUI
- Rewrite Core
- Reimplement IPC
- Add a second networking state machine
- Add a new state-management framework
- Introduce a plugin architecture
- Build a public administration portal
- Add unnecessary telemetry
- Introduce new authentication providers
- Add features unrelated to internal usage
- Make the Controller responsible for network policy
- Perform a large-scale refactor without an approved plan

The objective is a maintainable Controller that accurately reflects Core behavior, not maximum architectural complexity.

## 17. Definition of Done

A Controller change is complete when:

- The relevant behavior is implemented.
- Core remains the authority for networking state and decisions.
- IPC is used for all Core operations.
- Loading, success, failure, and reconnection behavior are handled appropriately.
- Asynchronous results cannot incorrectly overwrite newer presentation state.
- User-visible status reflects authoritative information.
- Errors are actionable where possible.
- Existing supported platforms remain functional.
- Relevant tests and builds have been run, or remaining verification is documented.
- The change does not introduce unrelated modifications.

## 18. Agent Behavior Rules

You are an implementation agent working within an established architecture.

You may suggest architectural improvements, but you must not silently redefine system semantics.

Ask for clarification before making decisions that affect:

- IPC protocol semantics
- Core authority boundaries
- ACL precedence
- Identity resolution
- Authentication or authorization behavior
- Persistent configuration formats
- Cross-platform compatibility

For ordinary implementation details, use sound engineering judgment.

If the repository contradicts this document, inspect the existing implementation and report the discrepancy. Do not blindly rewrite working code to match an assumption.

## 19. First Task: Controller Audit

Before making any changes, perform a read-only audit.

Identify:

1. Controller entry points and application lifecycle.
2. GUI framework and module structure.
3. Navigation and state-management architecture.
4. IPC client implementation.
5. Request, response, and event models.
6. Snapshot and event synchronization behavior.
7. Error handling and reconnection logic.
8. Node, room, and ACL screens.
9. Any networking or policy logic incorrectly implemented in Controller.
10. Existing tests and build commands.
11. Cross-platform implementation differences.
12. Architectural risks and inconsistencies.

Produce a report containing:

- Current architecture
- Main data flows
- IPC integration
- State ownership
- Main technical debt
- Recommended improvements
- Files likely to be modified
- Compatibility risks
- Testing plan

Do not modify source code during this initial audit. Wait for approval before beginning a substantial refactor.