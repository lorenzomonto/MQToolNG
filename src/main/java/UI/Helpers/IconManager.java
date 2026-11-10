/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Helpers;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.UIManager;

/**
 * Application icons: Lucide SVG icons (ISC license, see Resources/Icons/LICENSE-lucide.txt).
 * They are drawn in black and recolored at paint time, so they follow the current theme.
 * Icons are created once and shared.
 *
 * @author jzhou
 */
public class IconManager {
    private static final String ICON_PATH = "Resources/Icons/";
    private static final int SIZE = 16;

    private static final Map<String, ImageIcon> cache = new ConcurrentHashMap<>();
    private static final Map<String, Icon> selectedCache = new ConcurrentHashMap<>();

    // Theme dependent colors (read on every paint)
    private static final Supplier<Color> FOREGROUND = () -> UIManager.getColor("Label.foreground");
    private static final Supplier<Color> MUTED = () -> UIManager.getColor("Label.disabledForeground");
    private static final Supplier<Color> ACCENT = () -> UIManager.getColor("Component.accentColor");
    // Fixed colors readable on both light and dark themes
    private static final Supplier<Color> GREEN = () -> new Color(0x22A06B);
    private static final Supplier<Color> RED = () -> new Color(0xE5484D);
    private static final Supplier<Color> ORANGE = () -> new Color(0xE8833A);
    private static final Supplier<Color> PURPLE = () -> new Color(0x8E6CEF);
    private static final Supplier<Color> TEAL = () -> new Color(0x1A9FB0);

    public ImageIcon MainIcon16(){
        return appIcon(16);
    }

    public ImageIcon MainIcon32(){
        return appIcon(32);
    }

    public ImageIcon MonitorIcon(){
        return icon("monitor-dot", FOREGROUND);
    }

    public ImageIcon PubIcon(){
        return icon("megaphone", ORANGE);
    }

    public ImageIcon SubIcon(){
        return icon("bell-ring", TEAL);
    }

    public ImageIcon AuthIcon(){
        return icon("shield-check", GREEN);
    }

    public ImageIcon StatusIcon(){
        return icon("activity", FOREGROUND);
    }

    public ImageIcon ResolveIcon(){
        return icon("circle-help", FOREGROUND);
    }

    public ImageIcon StartIcon(){
        return icon("play", GREEN);
    }

    public ImageIcon StopIcon(){
        return icon("square", RED);
    }

    public ImageIcon LeftIcon(){
        return icon("chevron-left", FOREGROUND);
    }
    public ImageIcon RightIcon(){
        return icon("chevron-right", FOREGROUND);
    }

    public ImageIcon AddNewIcon(){
        return icon("plus", FOREGROUND);
    }

    public ImageIcon SearchIcon(){
        return icon("search", MUTED);
    }

    public ImageIcon RefreshIcon(){
        return icon("refresh-cw", FOREGROUND);
    }

    public ImageIcon TemporaryObjectIcon(){
        return icon("hourglass", FOREGROUND);
    }

    public ImageIcon CSVIcon(){
        return icon("file-spreadsheet", GREEN);
    }
    public ImageIcon MessageIcon(){
        return icon("mail", FOREGROUND);
    }
    public ImageIcon QueueLocalIcon(){
        return icon("inbox", ACCENT);
    }

    public ImageIcon QueueRemoteIcon(){
        return icon("send", PURPLE);
    }

    public ImageIcon QueueAliasIcon(){
        return icon("link", TEAL);
    }

    public ImageIcon QueueTransmitIcon(){
        return icon("forward", ORANGE);
    }

    public ImageIcon QueueMgrConnectedIcon(){
        return icon("server", GREEN);
    }
    public ImageIcon QueueMgrDisconnectedIcon(){
        return icon("server-off", MUTED);
    }

    public ImageIcon PutMessageIcon(){
        return icon("file-plus", FOREGROUND);
    }

    public ImageIcon BrowseMessageIcon(){
        return icon("file-search", FOREGROUND);
    }

    public ImageIcon DeleteMessageIcon(){
        return icon("file-x", RED);
    }

    public ImageIcon PropertiesIcon(){
        return icon("sliders-horizontal", FOREGROUND);
    }

    public ImageIcon BackupMessageIcon(){
        return icon("download", FOREGROUND);
    }

    public ImageIcon RestoreMessageIcon(){
        return icon("upload", FOREGROUND);
    }

    public ImageIcon Delete(){
        return icon("trash-2", RED);
    }

    public ImageIcon DeviceManager(){
        return icon("network", FOREGROUND);
    }

    public ImageIcon Queue(){
        return icon("layers", ACCENT);
    }

    public ImageIcon Channel(){
        return icon("cable", PURPLE);
    }

    public ImageIcon Root(){
        return icon("boxes", FOREGROUND);
    }

    public ImageIcon SystemObjectsIcon(){
        return icon("settings-2", FOREGROUND);
    }

    public ImageIcon ConnectIcon(){
        return icon("log-in", GREEN);
    }

    public ImageIcon DisconnectIcon(){
        return icon("log-out", FOREGROUND);
    }

    public ImageIcon SaveContentIcon(){
        return icon("file-text", FOREGROUND);
    }

    public ImageIcon EditIcon(){
        return icon("pencil", FOREGROUND);
    }

    /** A Lucide icon of Resources/Icons drawn in the given color (16 px). */
    public static Icon Named(String name, Color color){
        return selectedCache.computeIfAbsent(name + "@" + Integer.toHexString(color.getRGB()), k -> {
            FlatSVGIcon icon = new FlatSVGIcon(ICON_PATH + name + ".svg", SIZE, SIZE);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color));
            return icon;
        });
    }

    /** Same icon drawn in a single color, e.g. the selection foreground of a selected tree row. */
    public static Icon InColor(Icon icon, Color color){
        if(!(icon instanceof FlatSVGIcon) || color == null){
            return icon;
        }
        FlatSVGIcon svgIcon = (FlatSVGIcon) icon;
        // A new instance: derive() may return the same icon, and its color filter must not change
        return selectedCache.computeIfAbsent(svgIcon.getName() + "#" + Integer.toHexString(color.getRGB()), k -> {
            FlatSVGIcon colored = new FlatSVGIcon(svgIcon.getName(), SIZE, SIZE);
            colored.setColorFilter(new FlatSVGIcon.ColorFilter(c -> color));
            return colored;
        });
    }

    private static ImageIcon icon(String name, Supplier<Color> color){
        return cache.computeIfAbsent(name, n -> {
            FlatSVGIcon icon = new FlatSVGIcon(ICON_PATH + n + ".svg", SIZE, SIZE);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> {
                Color themeColor = color.get();
                return themeColor != null ? themeColor : c;
            }));
            return icon;
        });
    }

    private static ImageIcon appIcon(int size){
        return cache.computeIfAbsent("app" + size, n -> new FlatSVGIcon(ICON_PATH + "app.svg", size, size));
    }
}
