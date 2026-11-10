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
- Fixes: UI updates moved to the Swing event thread, background connection to the queue manager,
  leaked connections and handles closed, debug output that printed connection passwords removed.

## Requirements

- Java 21 (JDK to build, JRE to run)
- Maven 3.9+

Dependencies (IBM MQ client 9.4, FlatLaf, Apache POI) are downloaded from Maven Central.
The `bounce` XML editor kit is not published there and is kept in `lib/repo`.

## Build and run

```
mvn package
java -jar target/MQAdminTool.jar
```

`ConnectionSettings.xml`, `Settings.xml` and the `Logs` folder are read and written in the working directory.

The project can be opened directly in NetBeans, IntelliJ IDEA or Eclipse as a Maven project.
The NetBeans GUI builder `.form` files are next to their classes in `src/main/java`.

## User interface

- FlatLaf look and feel with six themes (Settings > Skins) and the Inter font.
- Shared style in `src/main/resources/UI/themes/*.properties`.
- Icons are [Lucide](https://lucide.dev) SVG icons, recolored at paint time by `UI/Helpers/IconManager`
  so they follow the theme.

## Performance notes

- PCF agents are kept per queue manager (`MQApi/PCF/PCFAgentPool`) instead of being opened for every command.
- PCF replies are read with client read ahead (`MQApi/PCF/ReadAheadPCFMessageAgent`).
- Background tasks apply their results on the Swing event thread (`UI/Helpers/SwingThread`).

## License

MQToolNG is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3. See [LICENSE](LICENSE).

Third-party components and their licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
The IBM MQ client is not part of this repository: Maven downloads it at build time, and it is covered by IBM's own license.
