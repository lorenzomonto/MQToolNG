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
- Queue manager list in two sections, connected and not connected, with filter and refresh of all: each row
  shows the state (connecting, last connection error) and the version; quick actions on mouse over.
  A queue manager administered through another connected one is shown under it.
- Channel list: a dot for the channel status, and a yellow warning triangle for a retrying channel.
- Safe message operations: a backup that removes the messages removes them only once the file is
  complete, in units of work (and with a filter only the saved ones); editing a message, also keeping
  its position, and restoring a file run under syncpoint, so an error or a stop leaves no message lost.
- New message file format with the message properties (the old backups lost them), and files of IBM
  dmpmqmsg (qload) written and read.
- Configuration of a queue manager as an MQSC script (like dmpmqcfg), comparison of two queue managers
  with the commands that align the second one, and authority records (setmqaut) shown and changed.
- Message browser: JSON shown indented; message properties shown and changed.
- Automatic refresh of the lists and of the queue manager page, and a depth chart of a queue.
- Events of a queue manager shown as a log (authority failures, channels, full queues, configuration
  changes with the attributes changed, commands), and the event settings of the queue manager.
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

## Message files

- **Backup** (`.msg`, `MQApi/Utilities/MessageFile`): for each message its MQMD fields and message
  properties by name, and its bytes as they are on the queue (never converted). Gzip compressed or not;
  a file cut short is read up to its last whole message. Backups of MQAdminTool and older versions are read.
- **dmpmqmsg** (`MQApi/Utilities/DmpmqmsgFile`): choose "dmpmqmsg / qload text file" in the backup file
  chooser; the file loads with `dmpmqmsg -o QUEUE -f file`. Restore reads dmpmqmsg files of every data
  format (hex, `-dA`, `-dt`, `-dp`), recognized by their content.
- Removing after a backup, editing a message and restoring are done in units of work
  (`MQApi/Utilities/UnitOfWork`), committed in steps that follow the MAXUMSGS of the queue manager.

## Configuration and authorities

- Right click on a connected queue manager: **Export configuration (MQSC)**, **Compare with another queue
  manager** and **Authority records**; on a queue or channel: **Authorities**.
- The definitions are read with MQSC DISPLAY commands sent to the command server (PCF Escape,
  `MQApi/Config`), also for queue managers administered through another one; not on z/OS. Passwords
  are not exported; dates, depths and other state are left out.

## Events

- Right click on a connected queue manager: **Events…**, or **Show events** in *Needs attention* on its page.
- The event messages are browsed, not removed, from SYSTEM.ADMIN.QMGR, CHANNEL, PERFM, CONFIG, COMMAND and
  LOGGER.EVENT (`MQApi/Events/QueueManagerEvents`): the newest 10,000 of each queue. A configuration change
  (two messages, the object before and after) is shown as one event with the attributes changed.
- **Event settings** turns each kind of event on or off (AUTHOREV, CHLEV, CONFIGEV, CMDEV… of ALTER QMGR).
- Not available for a queue manager administered through another one: MQ reads only the queues of the
  queue manager it is connected to. The error log files (AMQERR01.LOG) cannot be read through MQ.

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
- The message list reads only the first 16 KB of each message, with read ahead: 200 messages of 1 MB
  are listed in 0.15 s instead of 1.9 s (1.5 s instead of 10.6 s on a link with 40 ms round trip).
  Total length and data length stay those of the whole message (`MQUtility.DataLength`).
- The queue manager page sends its 7 commands at once and reads the replies together
  (`ReadAheadPCFMessageAgent.SendAll`): 70 ms instead of 620 ms on a link with 40 ms round trip.

## Support the project

MQToolNG is free. If it is useful to you, you can support its development with a donation on
[PayPal](https://www.paypal.me/LorenzoMontoro).

## License

MQToolNG is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3. See [LICENSE](LICENSE).

Third-party components and their licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
The IBM MQ client is not part of this repository: Maven downloads it at build time, and it is covered by IBM's own license.
