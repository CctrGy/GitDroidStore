# GitDroidStore

GitDroidStore es una mini-store Android que descubre aplicaciones publicadas por un usuario de GitHub, descarga sus APK desde GitHub Releases, verifica su integridad y firma digital y solicita su instalación mediante `PackageInstaller.Session`.

Repositorio oficial: [CctrGy/GitDroidStore](https://github.com/CctrGy/GitDroidStore). El maker configurado de forma predeterminada es `CctrGy`.

## Requisitos para publicar una aplicación

Para que GitDroidStore reconozca un repositorio como una aplicación instalable debe cumplir todos estos requisitos:

1. El repositorio de GitHub debe ser público.
2. Debe tener al menos una GitHub Release publicada.
3. La última Release debe contener un asset llamado exactamente `app.apk`.
4. El asset debe estar completamente subido y tener estado `uploaded`.
5. `app.apk` debe ser un APK Android válido y estar firmado digitalmente.
6. La raíz de la rama predeterminada debe contener un `version.json` válido para permitir una primera instalación segura.
7. El certificado real del APK debe coincidir con `certificateSha256`.
8. Si se declaran `packageName` o `sha256`, también deben coincidir con el APK publicado.

Los borradores y las prereleases no se consideran la última versión porque GitDroidStore consulta:

```text
GET https://api.github.com/repos/<usuario>/<repositorio>/releases/latest
```

## Estructura del repositorio

Los metadatos se guardan en la raíz del repositorio; el APK no se guarda en Git:

```text
MiAplicacion/
├── version.json       obligatorio para la primera instalación
├── icon.png           opcional
├── appname.txt        opcional
└── código fuente      opcional para GitDroidStore
```

Cada versión del APK se adjunta a una Release:

```text
Releases
├── v1.0.0
│   └── app.apk
├── v1.1.0
│   └── app.apk
└── v2.0.0
    └── app.apk
```

El nombre `app.apk` distingue mayúsculas y minúsculas. Nombres como `App.apk`, `mi-app.apk` o `app-release.apk` no serán reconocidos.

## Archivo `version.json`

Ejemplo completo:

```json
{
  "packageName": "com.example.myapp",
  "versionName": "1.2.0",
  "versionCode": 12,
  "sha256": "93d709f7c42b0c2f0f0b62103bfeeb51f92d36c9dc89bf66da07f67cb46f24f7",
  "certificateSha256": "f4ca57320b90ab1d0121d2db5fa3f8a177da3f091bf396bf55666559a782f1b7"
}
```

### `packageName`

- Recomendado.
- Debe ser el `applicationId` real incluido dentro del APK.
- Ejemplo: `com.example.myapp`.
- Si se declara y no coincide con el APK descargado, la instalación se bloquea.
- No debe cambiar entre actualizaciones. Cambiarlo crea una aplicación Android diferente.

### `versionName`

- Recomendado para mostrar una versión amigable, como `1.2.0`.
- Si se omite, GitDroidStore utiliza el tag de la Release y elimina un prefijo `v` minúsculo. Por ejemplo, `v1.2.0` se muestra como `1.2.0`.
- Es informativo; Android decide las actualizaciones utilizando `versionCode`.

### `versionCode`

- Recomendado para detectar actualizaciones antes de descargar el APK.
- Debe ser un número entero positivo.
- Debe coincidir con el `versionCode` usado al compilar el APK.
- Cada actualización debe utilizar un número superior al anterior: `1`, `2`, `3`, etc.
- GitDroidStore bloquea un APK cuyo `versionCode` real sea inferior al de la aplicación instalada.

### `sha256`

- Opcional cuando GitHub proporciona el campo `digest` del asset de la Release.
- Es el SHA-256 del archivo `app.apk` completo, no el del certificado.
- Debe escribirse en hexadecimal; se aceptan mayúsculas, minúsculas y separadores `:`.
- Si GitHub proporciona un digest y `version.json` declara otro diferente, el repositorio se descarta.
- Tras descargar, GitDroidStore vuelve a calcular el SHA-256 y bloquea el archivo si no coincide.

En PowerShell puede calcularse con:

```powershell
(Get-FileHash .\app.apk -Algorithm SHA256).Hash.ToLowerInvariant()
```

En Linux o macOS:

```bash
sha256sum app.apk
```

### `certificateSha256`

- Obligatorio para la primera instalación.
- Es el SHA-256 del certificado con el que se firmó el APK; no es el hash del archivo APK.
- Actúa como ancla de confianza: evita que alguien que controle el repositorio sustituya la aplicación por otro APK firmado con una clave distinta.
- Todas las actualizaciones deben conservar la misma clave de firma.
- GitDroidStore compara este valor con el certificado real del APK y, cuando la aplicación ya está instalada, también con el certificado de la versión instalada.
- Actualmente GitDroidStore no admite rotación de claves. No cambies el keystore sin implementar antes una política de migración.

Puede obtenerse con `apksigner`, incluido en Android SDK Build Tools:

```powershell
apksigner verify --print-certs app.apk
```

Busca una línea semejante a:

```text
Signer #1 certificate SHA-256 digest: f4ca57320b90ab1d0121d2db5fa3f8a177da3f091bf396bf55666559a782f1b7
```

También puede consultarse directamente desde el keystore:

```powershell
keytool -list -v -keystore mi-clave.jks -alias mi-alias
```

No publiques el archivo `.jks`, `.keystore`, sus contraseñas ni ninguna clave privada. Solo se publica la huella SHA-256 del certificado.

## Archivos opcionales

### `appname.txt`

Contiene únicamente el nombre amigable de la aplicación:

```text
Mi Aplicación
```

GitDroidStore utiliza como máximo los primeros 100 caracteres. Si el archivo falta o está vacío, usa el nombre del repositorio.

### `icon.png`

Icono público de la aplicación. Debe llamarse exactamente `icon.png` y estar en la raíz de la rama predeterminada. Si se omite, GitDroidStore utiliza un icono genérico.

## Crear una Release compatible

1. Compila el APK en modo release.
2. Firma el APK con el keystore permanente de esa aplicación.
3. Comprueba el APK con `apksigner verify --print-certs app.apk`.
4. Calcula su SHA-256.
5. Actualiza `version.json` en la rama predeterminada.
6. Crea un tag, por ejemplo `v1.2.0`.
7. Crea una GitHub Release para ese tag.
8. Adjunta el APK con el nombre exacto `app.apk`.
9. Publica la Release; no la dejes como borrador ni prerelease.

No reemplaces silenciosamente el APK de una Release antigua. Publica una Release nueva para conservar un historial verificable.

## Validaciones realizadas antes de instalar

GitDroidStore ejecuta estas comprobaciones:

```text
Repositorio público
        ↓
Última Release publicada
        ↓
Asset app.apk en estado uploaded
        ↓
URL HTTPS oficial de GitHub Releases
        ↓
Descarga y cálculo SHA-256
        ↓
APK Android válido y firmado
        ↓
packageName coincide, si fue declarado
        ↓
SHA-256 coincide con GitHub/version.json
        ↓
certificateSha256 coincide con el APK
        ↓
Certificado coincide con la app instalada
        ↓
versionCode no es inferior al instalado
        ↓
PackageInstaller.Session
```

El APK temporal se elimina si cualquiera de estas comprobaciones falla.

## Motivos por los que una aplicación no aparece

- El repositorio es privado.
- No existe ninguna Release publicada.
- Solo existen borradores o prereleases.
- La última Release no contiene `app.apk`.
- El asset tiene otro nombre o todavía no está completamente subido.
- La URL del asset no pertenece a GitHub Releases.
- El SHA-256 de `version.json` contradice al digest proporcionado por GitHub.
- Se alcanzó el límite de consultas de la API de GitHub; puede configurarse un token personal opcional.

## Motivos por los que una instalación se bloquea

- El archivo descargado no es un APK válido.
- El APK no está firmado.
- Falta `certificateSha256` durante la primera instalación.
- El certificado real no coincide con `certificateSha256`.
- La firma no coincide con la aplicación instalada.
- El SHA-256 calculado no coincide con el esperado.
- El `packageName` declarado no coincide con el APK.
- Se intenta instalar un `versionCode` inferior al instalado.
- Android o la política del dispositivo bloquean la instalación.

## Autoactualización de GitDroidStore

GitDroidStore utiliza exactamente el mismo sistema para actualizarse. El repositorio `CctrGy/GitDroidStore` debe publicar su APK firmado como `app.apk` en una Release y mantener `version.json` en la raíz. Todas las versiones deben conservar la misma clave de firma y el package name `com.gitdroidstore`.

## Restricciones de Android

El permiso `REQUEST_INSTALL_PACKAGES` permite que el usuario autorice GitDroidStore como origen de instalación. No concede instalación silenciosa a una aplicación convencional. Android puede devolver `STATUS_PENDING_USER_ACTION` y exigir una confirmación del sistema.

La instalación completamente silenciosa solo está disponible normalmente para un *device owner*, un *profile owner* afiliado o una aplicación privilegiada del sistema.

## Desarrollo de GitDroidStore

Requisitos para compilar este proyecto:

- JDK 17.
- Gradle 9.5 o posterior compatible.
- Android Gradle Plugin 9.3.
- Android SDK Platform 37.
- Android SDK Build Tools 36.0.0.
- Android 8.0 (API 26) como versión mínima del dispositivo.

El token de GitHub es opcional y solo se utiliza para aumentar el límite de consultas de la API.
