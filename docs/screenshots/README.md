# Screenshots

Add screenshots of your telepath here and show them at the top of the README.

Take one from a computer with adb, with Home on screen:

```sh
adb exec-out screencap -p > home.png
```

Before you publish one, check it for anything private: your own photos, the titles in Continue Watching, your
watchlist, and the names of your inputs. A built-in wallpaper (Home settings → Appearance) keeps the background
neutral.

## The tour at the top of the README

`telepath-tour.webp` is a screen recording of a TV, converted to an animated WebP so that GitHub plays it in the
README. To record your own, start the recording with Home on screen and use the remote while it runs (up to 60
seconds here):

```sh
adb shell screenrecord --time-limit 60 /sdcard/tour.mp4
adb pull /sdcard/tour.mp4
ffmpeg -i tour.mp4 -an -vf "fps=12,scale=1280:-2" -c:v libwebp_anim -q:v 80 -loop 0 telepath-tour.webp
```

Keep the WebP to a few megabytes: if it's bigger, lower `-q:v` or the frame rate (`fps=`).