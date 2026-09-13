# UPX Compress Plugin

A Jenkins build step that compresses the executable produced by a build
(Delphi 7, Delphi/RAD Studio, .NET, etc.) using [UPX](https://upx.github.io/).

UPX itself is not bundled with the plugin: it is downloaded on demand from
the official [upx/upx GitHub releases](https://github.com/upx/upx/releases)
by a configured **UPX installation** (see below), matching the OS/architecture
of the node the build runs on, and its SHA-256 is cross-checked against the
digest published by GitHub's release API when available.

## Configuring a UPX installation

Under **Manage Jenkins > Tools**, add a **UPX installation** and pick
"Download from upx/upx GitHub releases" as its installer, entering the UPX
version you want (e.g. `5.2.1`). You can configure more than one version if
different jobs need different ones.

## Usage

In a Freestyle job: **Add build step > Compress executable with UPX**, then
pick the UPX installation, the executable name, and (optionally) the
options.

| Field       | Description                                                    | Default          |
|-------------|------------------------------------------------------------------|------------------|
| UPX installation | Which configured UPX version to use                        | (required)       |
| Executable  | Path relative to the workspace, e.g. `Project.exe`               | (required)       |
| Options     | Command-line arguments passed to UPX                              | `--best --lzma`  |

In a Pipeline:

```groovy
upxCompress upxName: 'upx-5.2.1', executable: "${PROJECT}.exe", options: '--best --lzma'
```

## License

Plugin code: MIT (see `LICENSE`).
UPX itself (downloaded at build time, not redistributed by this repository)
is licensed by the upx/upx project under GPL-2.0-or-later with an exception
for compressed executables — see https://github.com/upx/upx/blob/master/LICENSE.
