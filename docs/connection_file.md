# Connection files

A connection file carries SambaLite connection settings from one device to others. Set up the
connections once, export them, and import the file on every phone; each user then enters their
own username and password. A file can also be written by hand from the format below.

## Export

On the start screen, open the menu and choose **Export connections**. Select the connections,
decide whether usernames should be included, and pick where to save the file
(`sambalite-connections.json`).

- **Passwords are never exported.**
- Usernames are left out unless you choose to include them.
- A connection without a username (guest access) is exported as guest access.

## Import

On the start screen, open the menu and choose **Import connections**, then pick the file. SambaLite
lists the connections it found; connections that already exist (same server, port and share) are
marked and not selected. Tap **Import** to add the selected ones.

Imported connections without a username or password are marked **Credentials needed**. Tapping
such a connection opens it for editing, so you can enter your credentials (and choose a share, if
the file has none) before it connects.

If the file contains passwords, the import says so. The passwords are saved like ones entered by
hand, but the file itself stores them in plain text: delete it after the import and do not send
it around.

## Format

The file is JSON in UTF-8:

```json
{
  "format": "sambalite-connections",
  "version": 1,
  "connections": [
    {
      "name": "NAS Data",
      "server": "nas.local",
      "port": 445,
      "share": "Data",
      "initialPath": "Projects/2026",
      "domain": "WORKGROUP",
      "username": "",
      "password": "",
      "encryptData": true,
      "signingRequired": false,
      "asyncTransport": false
    },
    {
      "name": "Public folder",
      "server": "192.168.2.5",
      "share": "public",
      "guest": true
    }
  ]
}
```

| Field | Required | Default | Meaning |
|---|---|---|---|
| `format` | no | `sambalite-connections` | Identifies the file. Any other value is rejected. |
| `version` | no | `1` | Format version. Files with a higher version need a newer SambaLite. |
| `connections` | **yes** | | The list of connections. At most 500. |

Per connection:

| Field | Required | Default | Meaning |
|---|---|---|---|
| `server` | **yes** | | Host name or IP address (IPv4 or IPv6). `\\nas` is accepted and means `nas`. No share or path here. |
| `name` | no | the server | Name shown in the connection list. |
| `port` | no | `445` | SMB port, 1–65535. |
| `share` | no | empty | Share name, without slashes. If empty, the user chooses one after the import. |
| `initialPath` | no | empty | Folder inside the share that opens on connect, e.g. `Photos/2024`. |
| `domain` | no | empty | Windows domain or workgroup. |
| `username` | no | empty | Leave empty or omit it so each user enters their own. |
| `password` | no | empty | Leave empty or omit it. If set, it is imported (see above). |
| `guest` | no | `false` | `true` for guest access without credentials; `username` and `password` are then ignored. |
| `encryptData` | no | `false` | Require SMB3 encryption. |
| `signingRequired` | no | `false` | Require SMB signing. |
| `asyncTransport` | no | `false` | Use the asynchronous transport. |

Unknown fields are ignored. A connection with an invalid value (no server, a server with a slash or
space, a port outside 1–65535, a share with `/ \ : * ? " < > |`) is skipped and listed in the
import with the reason; the other connections are imported.

Without `"guest": true`, a connection whose username or password is empty is imported as
**Credentials needed**, so an empty template never logs in anonymously by accident.
