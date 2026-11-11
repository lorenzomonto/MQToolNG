/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package UI.Models;

/**
 *
 * @author Jianbin Zhou
 */
public class SettingsModel {
    public String SkinName;
    /** Ask GitHub at startup whether a new version is available. */
    public boolean CheckForUpdates = true;
    /** Version the user chose not to be told about again. */
    public String SkippedVersion;
}
