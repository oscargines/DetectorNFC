# Desarrollo NFC para documentos de identidad electrónicos en Europa

Sí. El panorama europeo es más amplio que el del DNIe español, aunque
está fragmentado. No existe un SDK único europeo para acceder por NFC a
todos los documentos de identidad, sino SDK y middleware por país,
mientras que la UE impulsa un marco común mediante **eIDAS 2.0** y la
futura **EU Digital Identity Wallet**.

## Países con SDK o middleware oficial

País Documento SDK oficial NFC

---

España DNIe 3.0 / 4.0 Sí ✅
Alemania Personalausweis AusweisApp SDK ✅
Bélgica eID Middleware + PKCS#11 Principalmente smartcard
Italia CIE 3.0 SDK CIE ID ✅
Estonia ID Card Middleware oficial Principalmente smartcard
Polonia eDO APIs oficiales ✅

## Tecnología utilizada

La mayoría de los documentos utilizan estándares comunes:

- ISO 14443 (NFC)
- ISO 7816 (tarjetas inteligentes)
- ICAO 9303
- PACE, BAC y EAC para acceso seguro

Las diferencias están en:

- Estructura de los ficheros.
- Certificados.
- Mecanismos de autenticación.
- Políticas de acceso.

## ¿Existe una API común europea?

No para el acceso NFC directo.

Sí existe un marco de interoperabilidad:

- eIDAS
- eIDAS 2.0
- EU Digital Identity Wallet

Está orientado a la identidad digital y autenticación remota, no a la
lectura del chip NFC.

## Arquitectura típica

```text
            NFC Reader
                 │
        ISO 14443 / ISO 7816
                 │
       Detección del documento
                 │
     ┌───────────┴────────────┐
     │                        │
  DNI España            CIE Italia
     │                        │
 SDK oficial          SDK oficial
     │                        │
 Personalausweis      eDO Polonia
     │                        │
 SDK oficial          API oficial
```

## Evitar implementar un SDK por país

Existen plataformas comerciales que unifican el acceso mediante una
única API compatible con múltiples esquemas nacionales.

## Lectura directa del chip NFC

Si el objetivo es leer el chip (DG1, DG2, DG11, certificados, firmas,
etc.), muchos documentos siguen la arquitectura de los pasaportes
electrónicos, por lo que bibliotecas como **JMRTD**, **OpenPACE** o
**NFCPassportReader** pueden servir como base. Para autenticación
avanzada suelen ser necesarias implementaciones específicas de cada
país.

## Conclusión

Es viable desarrollar una librería multiplataforma (Android, iOS,
Windows y Linux) capaz de leer el DNI español y otros documentos
europeos mediante una capa común basada en ISO 14443, ISO 7816 y
PACE/EAC, complementada con adaptadores específicos para cada país.

# SDKs y Herramientas de Desarrollo para Documentos de Identidad Electrónicos Europeos

## España

### DNI electrónico (DNIe)

Portal oficial para desarrolladores:

https://www.dnielectronico.es/PortalDNIe/PRF1_Cons02.action?pag=REF_1120

Incluye:

- SDK oficial
- Middleware
- Documentación técnica
- PKCS#11
- Ejemplos de integración

---

## Alemania

### AusweisApp SDK

Portal para desarrolladores:

https://www.ausweisapp.bund.de/en/software-development

SDK:

https://www.ausweisapp.bund.de/en/software-development-kit-sdk

Incluye:

- SDK Android
- SDK iOS (Swift)
- SDK Wrapper
- Documentación
- Código abierto
- Infraestructura de pruebas

---

## Italia

### CIE (Carta d'Identità Elettronica)

Portal para desarrolladores:

https://docs.italia.it/

Proyecto CIE ID:

https://www.cartaidentita.interno.gov.it/

Incluye:

- SDK Android
- SDK iOS
- APIs de autenticación
- Integración NFC

---

## Bélgica

### Belgian eID Middleware

Portal oficial:

https://eid.belgium.be

Incluye:

- Middleware oficial
- PKCS#11
- Librerías Java
- Smart Card API
- Ejemplos

---

## Estonia

### ID Card

Portal para desarrolladores

https://www.id.ee/en/for-developers/

Incluye:

- DigiDoc
- Web eID
- Librerías Java
- APIs REST
- Ejemplos

---

## Polonia

### eDO

Portal oficial

https://www.gov.pl/web/e-dowod

Aplicación oficial

https://www.edoapp.pl/

---

## Web eID (Proyecto Europeo)

https://web-eid.eu/

Proyecto Open Source que permite utilizar múltiples tarjetas eID europeas.

Actualmente soporta:

- Estonia
- Finlandia
- Letonia
- Lituania
- Bélgica
- Croacia

---

# Librerías Open Source

## OpenPACE

https://github.com/frankmorgner/openpace

Implementa:

- PACE
- EAC
- Chip Authentication
- Terminal Authentication

Muy utilizada para documentos ICAO 9303.

---

## JMRTD

https://github.com/E3V3A/JMRTD

Biblioteca Java para:

- Pasaportes electrónicos
- Documentos ICAO
- NFC
- DG1
- DG2
- DG11
- Certificados

Compatible con numerosos documentos europeos.

---

## PCSC

https://pcsclite.apdu.fr/

API estándar para lectores de tarjetas inteligentes.

Disponible para:

- Windows
- Linux
- macOS

---

## OpenSC

https://github.com/OpenSC/OpenSC

Proyecto Open Source para trabajar con:

- Smart Cards
- PKCS#11
- PIV
- eID

Compatible con multitud de tarjetas nacionales.

---

# Herramientas NFC

## Android

android.nfc

Documentación:

https://developer.android.com/guide/topics/connectivity/nfc

---

## iOS

Core NFC

https://developer.apple.com/documentation/corenfc

---

# Estándares importantes

ISO 14443
ISO 7816
ICAO 9303
PACE
BAC
EAC
PKCS#11
PC/SC
ASN.1
BER-TLV

---

# Recomendación para un proyecto multiplataforma

La combinación más potente actualmente sería:

- OpenPACE
- OpenSC
- PCSC
- JMRTD
- SDK oficial del DNIe
- AusweisApp SDK
- SDK CIE italiano
- Web eID

Con esta base es posible implementar una capa común para la lectura de la mayoría de documentos electrónicos europeos mediante NFC.
