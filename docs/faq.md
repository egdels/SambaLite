
# SambaLite FAQ

**Frequently Asked Questions and Solutions for Samba Connections with SambaLite**

---

## 1. **I can't connect to my Samba server. What can I do?**

- Double-check the IP address and share name.
- Make sure your server is on the same network as your device.
- Test the connection with another Samba client (e.g., `smbclient` or VLC) to rule out server issues.

---

## 2. **Error: "NT_STATUS_LOGON_FAILURE" or "Authentication failed"**

- Check your username and password.
- Pay attention to upper/lower case.
- If you want to connect without credentials, the share must be configured with **guest ok = yes**.
- Make sure the user exists on the Samba server and has a Samba password (`sudo smbpasswd <username>`).

---

## 3. **I can't create folders or upload files**

- Check if the share is set as **read only = yes**. Writing is only possible with **read only = no**.
- Make sure the shared folder has correct write permissions (e.g., `chmod 777` for testing).
- The user you log in as must have write permissions on the server.

---

## 4. **Guest access (anonymous login) does not work**

- The share must be set with **guest ok = yes**.
- In the `[global]` section, add **map to guest = Bad User**.
- Make sure a guest account exists (default is `nobody`).
- The shared folder must be readable by the guest account (`chmod 755` and `chown nobody:nogroup <folder>`).

---

## 5. **Error: "NT_STATUS_NO_SUCH_USER" or Guest access is denied**

- Check if the user is properly created (`sudo smbpasswd -a <username>`).
- For guest access, see previous point.

---

## 6. **I don't see the share in the list of available shares**

- Make sure **browseable = yes** is set in the share.
- Check if the firewall is blocking access (port 445/TCP must be open).
- Not all clients list all shares automatically – with SambaLite, you may need to enter the share name manually.

---

## 7. **How can I create server logs for troubleshooting?**

- Increase the log level in your smb.conf (`log level = 3` in `[global]`).
- Logs are usually found in `/var/log/samba/`.
- Typical command to view logs: `cat /var/log/samba/log.smbd` or `cat /var/log/samba/log.<clientname>`.

---

## 8. **How do I add a new user?**

```bash
sudo useradd -M -s /sbin/nologin <username>
sudo smbpasswd -a <username>
```

---

## 9. **My share is visible on the network, but I can't access it**

- Check the share permissions in your smb.conf.
- Make sure the user is allowed for the share (`valid users = <username>` in the share section).
- Verify that Linux filesystem permissions match the user.

---

## 10. **How can I enable guest access for a share?**

```ini
[public]
   path = /srv/public
   browseable = yes
   guest ok = yes
   read only = yes
```
And in the `[global]` section:
```ini
map to guest = Bad User
guest account = nobody
```
---

*This FAQ will be updated regularly.*
