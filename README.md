# MQToolNG

A Java GUI application for managing IBM MQ objects and messages: queues, channels, channel authentication
records, topics and subscriptions, with message browse, edit, backup and restore.

MQToolNG is a modified version of [MQAdminTool](https://github.com/bintoo/MQAdminTool) by bintoo,
released under the GNU General Public License v3.0.

## Changes from MQAdminTool

Modified in November 2026:

- Build moved from NetBeans/Ant to Maven, Java 21, IBM MQ client 9.4, Apache POI 5.
- Much faster PCF queries: agents are reused per queue manager and replies are read with client read ahead.
  Listing 2000 queues over a link with 10 ms latency takes about 0.8 s instead of about 23 s.
- New user interface: FlatLaf with light and dark themes, Inter font, SVG icons, modern table and tree.
- The name filter matches the names that contain the text, ignoring case.
- Connection passwords are encrypted with Windows DPAPI, or asked when connecting instead of being saved.
- TLS for the connections (optional), and fixes for XML external entities and backup file deserialization.
- Queue managers administered through another one, like MQ Explorer (Connect: Through ...): the commands
  reach them through the channels of the queue manager connected to. Messages can be put but not browsed.
- Fixes: UI updates moved to the Swing event thread, background connection to the queue manager,
  leaked connections and handles closed, debug output that printed connection passwords removed.

## Download

The portable Windows version (64 bit) is on the [Releases](https://github.com/lorenzomonto/MQToolNG/releases) page:
extract the zip and run `MQToolNG.exe`. Java and the IBM MQ client are included, nothing has to be installed.

## Requirements

- Java 21 (JDK to build, JRE to run)
- Maven 3.9+

Dependencies (IBM MQ client 9.4, FlatLaf, Apache POI) are downloaded from Maven Central.

## Build and run

```
mvn package
java -jar target/MQToolNG.jar
```

To build the portable Windows zip (on Linux or macOS, needs `curl`, `zip` and `sha256sum`):

```
packaging/windows/build-portable.sh
```

It creates `target/MQToolNG-<version>-windows-x64.zip` with `MQToolNG.exe` (launch4j), `MQToolNG.jar`
and an Eclipse Temurin 21 JRE for Windows.

`ConnectionSettings.xml`, `Settings.xml` and the `Logs` folder are read and written in the working directory.

The project can be opened directly in NetBeans, IntelliJ IDEA or Eclipse as a Maven project.
The NetBeans GUI builder `.form` files are next to their classes in `src/main/java`.

## Queue manager page

Selecting a connected queue manager shows its overview (`UI/Overview/QueueManagerOverviewPanel`):
status, version and key figures, the checks that need attention (full queues, stopped services and channels,
dead-letter queue, disk and log usage, pending events, security notes; `MQApi/Overview/QueueManagerHealth`),
the busiest queues, services, recovery log and key settings. **Properties** opens every attribute,
grouped and searchable (`UI/Overview/QueueManagerPropertiesDialog`). The data comes from INQUIRE QMGR,
QMGR STATUS, LISTENER STATUS, CHANNEL STATUS, the queue list and CHLAUTH (`MQApi/Overview/QueueManagerOverview`).

## Security

- **TLS** can be enabled per connection (Edit > Use TLS): TLS 1.2 or higher, TLS 1.3 only or a specific
  cipher suite; trusted certificates from the Windows certificate store or a trust store file; an optional
  client certificate (SSLCAUTH(REQUIRED)) and an optional expected server certificate name (SSLPEER).
  Without TLS the connection works as before, not encrypted: the queue manager page shows it.
  The channel must have a matching SSLCIPH (e.g. `ANY_TLS12_OR_HIGHER`). See `MQApi/Connection/TlsSettings`.
- Message contents are parsed as XML without DTDs or external entities (no XXE), see `XMLHelper`.
- Backup files are read with a deserialization filter that only accepts the types a backup contains
  (`MQUtility.BACKUP_FILE_FILTER`); backups made by MQAdminTool are still accepted.
- A refused password is not retried, so it counts as one failed login.

## Connection passwords

- A connection with a user and no saved password asks for the password when connecting;
  it is kept in memory only while the queue manager is connected (`MQApi/Connection/SessionPasswords`).
- Saved passwords are encrypted with Windows DPAPI (`MQApi/Connection/SecurePasswordStore`):
  `ConnectionSettings.xml` contains `EncryptedPassword` values that only the same Windows user on the
  same computer can decrypt. Passwords are never written in clear; the ones saved in clear by older
  versions are encrypted at the first start. Other systems have no secure store, so passwords are not saved there.

## User interface

- FlatLaf look and feel with six themes (Settings > Skins) and the Inter font.
- Shared style in `src/main/resources/UI/themes/*.properties`.
- Icons are [Lucide](https://lucide.dev) SVG icons, recolored at paint time by `UI/Helpers/IconManager`
  so they follow the theme.

## Performance notes

- PCF agents are kept per queue manager (`MQApi/PCF/PCFAgentPool`) instead of being opened for every command.
- PCF replies are read with client read ahead (`MQApi/PCF/ReadAheadPCFMessageAgent`).
- Background tasks apply their results on the Swing event thread (`UI/Helpers/SwingThread`).

## Support the project

MQToolNG is free. If it is useful to you, you can support its development with a donation on
[PayPal](https://www.paypal.me/LorenzoMontoro).

## License

MQToolNG is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3. See [LICENSE](LICENSE).

Third-party components and their licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
The IBM MQ client is not part of this repository: Maven downloads it at build time, and it is covered by IBM's own license.
