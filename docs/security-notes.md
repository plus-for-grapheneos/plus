# +PLUS for GrapheneOS: security notes

This is an early test version. These notes describe what the code does today; the overview with a table of every
check is at https://plus-for-grapheneos.github.io/#security.

## How a new system image reaches your phone

+PLUS builds each system image on your phone: the official GrapheneOS update (its signature checked against
GrapheneOS's keys built into +PLUS; older builds refused), plus the plus-ups you chose (the catalog checked against
the key named in its link, its signature, its expiry and its serial; each app's file hash, package and version checked
against the catalog, and its signer pinned the first time it is installed). Your own key, kept in the phone's
hardware-backed keystore and usable only within 30 minutes of unlocking the phone, signs the result. Android's A/B
update system writes it to the inactive slot.

## Scenario: someone else holds your phone and controls the update path

**The concern.** An adversary takes your phone, or controls the network or the plus-up catalog, perhaps with a stolen
catalog signing key. Can they get a malicious image installed and running, for example by letting the phone sign it
and then turning the phone off and on?

**What prevents it.** Installing and activating are separate steps:

1. +PLUS writes a new image to the inactive slot **without activating it**. Restarting, a crash, or holding the power
   button all boot the image you already had.
2. The new image is activated only when you tap **Restart to finish** and confirm a screen listing what changed:
   the GrapheneOS build, each plus-up and its version, any new signer or permission.
3. That confirmation needs an unlocked phone. Nothing on the lock screen can activate an image: the notification's
   Restart action first asks you to unlock.

So a person holding your locked phone can't activate an image, even with a valid key for the update path, because
they can't unlock the phone. Signing also needs the phone to have been unlocked recently, since your key is bound to
your screen lock.

**What remains.**
- **Malware already running on your unlocked phone** could get its own image signed while you use the phone. It would
  still need you to confirm "Restart to finish". Read the change list before confirming: an update you didn't expect,
  or a plus-up you didn't choose, is a reason to decline.
- **Coercion:** someone who can make you unlock the phone and confirm can install anything. No update system protects
  against that.
- **A stolen catalog key** lets an attacker publish a malicious plus-up update. It is still refused if the app's
  signer changed. If the signer is the same, it still needs your confirmation before it runs.
- **Your key backup** is as powerful as the key: whoever has it can sign updates your phone accepts, for example
  through recovery. Keep it offline.

## Other notes

- Hardware attestation shows your key, not GrapheneOS's: apps and services that check the verified-boot key (such as
  Auditor) notice the difference.
- The catalog at https://plus-for-grapheneos.github.io/catalog/ is signed with a staging key during the test phase; it
  is replaced by an offline release key before a release.
