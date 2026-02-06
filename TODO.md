## Render stereo properly.
- make buffer one eye size.
- fill buffer with transparent.
- allow minecraft and other mods to draw their huds or crafting interfaces.
- save buffer as hud buffer.
- render left and right eyes into new buffer (actual screen sized).
- apply hud buffer to left eye and then right eye, keep transparency transparent.

## about the mouse
we have left and right eyes.
Translate mouse coords from actual screen coords, to wirtual eye coords, if mouse on left eye, do nothing, if on right, substract width of left.
