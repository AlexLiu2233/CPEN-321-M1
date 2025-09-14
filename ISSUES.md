# M1

## List of issues

### Issue 1: [UNABLE TO EDIT BIO nor save empty bio]

**Description**:[Within manage profile, theres no way to edit your bio (after the initial bio), or have no bio]

**How it was fixed?**: [in ManageProfileScreen.kt, removed bio read only = true and removed Row(Modifier.focusProperties { canFocus = false })]

### Issue 2: [DELETE ACCOUT DOES NOT DELETE ACCOUT]

**Description**:[Upon clicking and confirm "delete account" in Profile, you are signed out but the profile is still there if you try and sign in again]

**How it was fixed?**: [Added deleteAccount() in ProfileViewModel to call profileRepository.deleteProfile().

Updated ProfileScreen onDeleteDialogConfirm to call deleteAccount(), and only clear auth + navigate after success.]

### Issue 3: [WHITE SCREEN FREEZE]

**Description**:[Upon clicking on the top left (above CPEN 321 - M1) the screen goes white, freezes and you can only exit out of the app and restart it (soft locked)]

**How it was fixed?**: [WRITE_ISSUE_SOLUTION]




...
