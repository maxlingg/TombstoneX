#!/system/bin/sh
MODPATH=${0%/*}
ui_print "=================================="
ui_print " TombstoneX SELinux Helper v2.1.0"
ui_print "=================================="
ui_print ""
ui_print "Installing SELinux policies..."
ui_print "Allowing system_server to register"
ui_print "custom Binder service..."
ui_print ""
ui_print "Disabling system freezer..."
ui_print ""
ui_print "Done!"
ui_print "Please reboot your device."
