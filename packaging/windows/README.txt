MQToolNG @VERSION@ - portable version for Windows (64 bit)
==========================================================

Start
-----
Extract the zip anywhere (e.g. your desktop or a USB drive) and run MQToolNG.exe.
No installation and no Java are needed: a Java runtime (Eclipse Temurin @JRE_VERSION@)
is included in the "jre" folder.

Windows may show a SmartScreen warning the first time, because the program is not signed:
choose "More info" and then "Run anyway".

Your data
---------
The program keeps its files in its own folder:
  ConnectionSettings.xml   queue manager connections
  Settings.xml             theme
  Logs\                    log files
To move or update MQToolNG, copy these files to the new folder.

Passwords
---------
Leave the password of a connection empty to be asked for it when connecting: it is then kept
in memory only while the queue manager is connected.
A saved password is encrypted with Windows DPAPI: only your Windows user, on this computer,
can decrypt it. On another computer or user the password is asked again when connecting.
Passwords saved in clear by MQAdminTool are encrypted automatically at the first start.

Contents
--------
  MQToolNG.exe             launcher
  MQToolNG.jar             the application, including the IBM MQ client
                           (it can also be started with: jre\bin\javaw.exe -jar MQToolNG.jar)
  jre\                     Java runtime
  LICENSE.txt              GNU General Public License v3.0
  THIRD-PARTY-NOTICES.md   licenses of the included components

The IBM MQ classes for Java (com.ibm.mq.allclient) are IBM MQ redistributable components,
licensed by IBM under the IBM International Program License Agreement.

Source code, documentation and bug reports: https://github.com/lorenzomonto/MQToolNG
