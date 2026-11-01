# Icon masters

`hyzion/` holds the full-size (128×128) masters of the Hyzion icon set. They are
not shipped. The mod ships 64×64 copies built from them:

```bash
python -I scripts/optimize-icons.py
```

That writes `src/main/resources/Common/UI/Custom/MysticEssentials/Icons/Hyzion/`
(premultiplied box downscale, alpha bleed at the edges, metadata stripped,
recompressed). `--check` reports any shipped icon that no longer matches its
master. To add an icon: put the master here, run the script, then add a constant
and a picker entry to `HyzionIcons`.
