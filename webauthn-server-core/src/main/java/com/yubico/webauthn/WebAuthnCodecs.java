// Copyright (c) 2018, Yubico AB
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.yubico.webauthn;

import COSE.CoseException;
import com.google.common.primitives.Bytes;
import com.upokecenter.cbor.CBORObject;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.COSEAlgorithmIdentifier;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

final class WebAuthnCodecs {

  private static final ByteArray EC_PUBLIC_KEY_OID =
      new ByteArray(
          new byte[] {
            0x2A, -122, 0x48, -50, 0x3D, 0x02, 0x01
          }); // OID 1.2.840.10045.2.1 ecPublicKey (ANSI X9.62 public key type)
  private static final ByteArray P256_CURVE_OID =
      new ByteArray(
          new byte[] {0x2A, -122, 0x48, -50, 0x3D, 0x03, 0x01, 7}); // OID 1.2.840.10045.3.1.7
  private static final ByteArray P384_CURVE_OID =
      new ByteArray(new byte[] {0x2B, -127, 0x04, 0, 34}); // OID 1.3.132.0.34
  private static final ByteArray P512_CURVE_OID =
      new ByteArray(new byte[] {0x2B, -127, 0x04, 0, 35}); // OID 1.3.132.0.35

  private static final ByteArray ED25519_CURVE_OID =
      new ByteArray(new byte[] {0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70});

  static ByteArray ecPublicKeyToRaw(ECPublicKey key) {

    final int fieldSizeBytes =
        Math.toIntExact(
            Math.round(Math.ceil(key.getParams().getCurve().getField().getFieldSize() / 8.0)));
    byte[] x = key.getW().getAffineX().toByteArray();
    byte[] y = key.getW().getAffineY().toByteArray();
    byte[] xPadding = new byte[Math.max(0, fieldSizeBytes - x.length)];
    byte[] yPadding = new byte[Math.max(0, fieldSizeBytes - y.length)];

    Arrays.fill(xPadding, (byte) 0);
    Arrays.fill(yPadding, (byte) 0);

    return new ByteArray(
        Bytes.concat(
            new byte[] {0x04},
            xPadding,
            Arrays.copyOfRange(x, Math.max(0, x.length - fieldSizeBytes), x.length),
            yPadding,
            Arrays.copyOfRange(y, Math.max(0, y.length - fieldSizeBytes), y.length)));
  }

  static ByteArray rawEcKeyToCose(ByteArray key) {
    final byte[] keyBytes = key.getBytes();
    final int len = keyBytes.length;
    final int lenSub1 = keyBytes.length - 1;
    if (!(len == 64
        || len == 96
        || len == 132
        || (keyBytes[0] == 0x04 && (lenSub1 == 64 || lenSub1 == 96 || lenSub1 == 132)))) {
      throw new IllegalArgumentException(
          String.format(
              "Raw key must be 64, 96 or 132 bytes long, or start with 0x04 and be 65, 97 or 133 bytes long; was %d bytes starting with %02x",
              keyBytes.length, keyBytes[0]));
    }
    final int start = (len == 64 || len == 96 || len == 132) ? 0 : 1;
    final int coordinateLength = (len - start) / 2;

    final Map<Long, Object> coseKey = new HashMap<>();
    coseKey.put(1L, 2L); // Key type: EC

    final COSEAlgorithmIdentifier coseAlg;
    final int coseCrv;
    switch (len - start) {
      case 64:
        coseAlg = COSEAlgorithmIdentifier.ES256;
        coseCrv = 1;
        break;
      case 96:
        coseAlg = COSEAlgorithmIdentifier.ES384;
        coseCrv = 2;
        break;
      case 132:
        coseAlg = COSEAlgorithmIdentifier.ES512;
        coseCrv = 3;
        break;
      default:
        throw new RuntimeException(
            "Failed to determine COSE EC algorithm. This should not be possible, please file a bug report.");
    }
    coseKey.put(3L, coseAlg.getId());
    coseKey.put(-1L, coseCrv);

    coseKey.put(-2L, Arrays.copyOfRange(keyBytes, start, start + coordinateLength)); // x
    coseKey.put(
        -3L,
        Arrays.copyOfRange(keyBytes, start + coordinateLength, start + 2 * coordinateLength)); // y

    return new ByteArray(CBORObject.FromObject(coseKey).EncodeToBytes());
  }

  static PublicKey importCosePublicKey(ByteArray key)
      throws CoseException, IOException, InvalidKeySpecException, NoSuchAlgorithmException {
    CBORObject cose = CBORObject.DecodeFromBytes(key.getBytes());
    final int kty = cose.get(CBORObject.FromObject(1)).AsInt32();
    switch (kty) {
      case 1:
        // COSE-JAVA is hardcoded to ed25519-java provider ("EdDSA") which would require an
        // additional dependency to parse EdDSA keys via the OneKey constructor
        return importCoseEdDsaPublicKey(cose);
      case 2:
        return importCoseEcdsaPublicKey(cose);
      case 3:
        // COSE-JAVA supports RSA in v1.1.0 but not in v1.0.0
        return importCoseRsaPublicKey(cose);
      default:
        throw new IllegalArgumentException("Unsupported key type: " + kty);
    }
  }

  private static PublicKey importCoseRsaPublicKey(CBORObject cose)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    RSAPublicKeySpec spec =
        new RSAPublicKeySpec(
            new BigInteger(1, cose.get(CBORObject.FromObject(-1)).GetByteString()),
            new BigInteger(1, cose.get(CBORObject.FromObject(-2)).GetByteString()));
    return KeyFactory.getInstance("RSA").generatePublic(spec);
  }

  private static PublicKey importCoseEcdsaPublicKey(CBORObject cose)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    final int crv = cose.get(CBORObject.FromObject(-1)).AsInt32Value();
    final ByteArray x = new ByteArray(cose.get(CBORObject.FromObject(-2)).GetByteString());
    final ByteArray y = new ByteArray(cose.get(CBORObject.FromObject(-3)).GetByteString());

    final ByteArray curveOid;
    switch (crv) {
      case 1:
        curveOid = P256_CURVE_OID;
        break;

      case 2:
        curveOid = P384_CURVE_OID;
        break;

      case 3:
        curveOid = P512_CURVE_OID;
        break;

      default:
        throw new IllegalArgumentException("Unknown COSE EC2 curve: " + crv);
    }

    final ByteArray algId =
        encodeDerSequence(encodeDerObjectId(EC_PUBLIC_KEY_OID), encodeDerObjectId(curveOid));

    final ByteArray rawKey =
        encodeDerBitStringWithZeroUnused(
            new ByteArray(new byte[] {0x04}) // Raw EC public key with x and y
                .concat(x)
                .concat(y));

    final ByteArray x509Key = encodeDerSequence(algId, rawKey);

    KeyFactory kFact = KeyFactory.getInstance("EC");
    return kFact.generatePublic(new X509EncodedKeySpec(x509Key.getBytes()));
  }

  private static ByteArray encodeDerLength(final int length) {
    if (length <= 127) {
      return new ByteArray(new byte[] {(byte) length});
    } else if (length <= 0xffff) {
      if (length <= 255) {
        return new ByteArray(new byte[] {-127, (byte) length});
      } else {
        return new ByteArray(new byte[] {-126, (byte) (length >> 8), (byte) (length % 0x0100)});
      }
    } else {
      throw new UnsupportedOperationException("Too long: " + length);
    }
  }

  private static ByteArray encodeDerObjectId(final ByteArray oid) {
    return new ByteArray(new byte[] {0x06, (byte) oid.size()}).concat(oid);
  }

  private static ByteArray encodeDerBitStringWithZeroUnused(final ByteArray content) {
    return new ByteArray(new byte[] {0x03})
        .concat(encodeDerLength(1 + content.size()))
        .concat(new ByteArray(new byte[] {0}))
        .concat(content);
  }

  private static ByteArray encodeDerSequence(final ByteArray... items) {
    final ByteArray content =
        Stream.of(items).reduce(ByteArray::concat).orElseGet(() -> new ByteArray(new byte[0]));
    return new ByteArray(new byte[] {0x30}).concat(encodeDerLength(content.size())).concat(content);
  }

  private static PublicKey importCoseEdDsaPublicKey(CBORObject cose)
      throws InvalidKeySpecException, NoSuchAlgorithmException {
    final int curveId = cose.get(CBORObject.FromObject(-1)).AsInt32();
    switch (curveId) {
      case 6:
        return importCoseEd25519PublicKey(cose);
      default:
        throw new IllegalArgumentException("Unsupported EdDSA curve: " + curveId);
    }
  }

  private static PublicKey importCoseEd25519PublicKey(CBORObject cose)
      throws InvalidKeySpecException, NoSuchAlgorithmException {
    final ByteArray rawKey = new ByteArray(cose.get(CBORObject.FromObject(-2)).GetByteString());
    final ByteArray x509Key =
        new ByteArray(new byte[] {0x30, (byte) (ED25519_CURVE_OID.size() + 3 + rawKey.size())})
            .concat(ED25519_CURVE_OID)
            .concat(new ByteArray(new byte[] {0x03, (byte) (rawKey.size() + 1), 0}))
            .concat(rawKey);

    KeyFactory kFact = KeyFactory.getInstance("EdDSA");
    return kFact.generatePublic(new X509EncodedKeySpec(x509Key.getBytes()));
  }

  static String getJavaAlgorithmName(COSEAlgorithmIdentifier alg) {
    switch (alg) {
      case EdDSA:
        return "EDDSA";
      case ES256:
        return "SHA256withECDSA";
      case ES384:
        return "SHA384withECDSA";
      case ES512:
        return "SHA512withECDSA";
      case RS256:
        return "SHA256withRSA";
      case RS384:
        return "SHA384withRSA";
      case RS512:
        return "SHA512withRSA";
      case RS1:
        return "SHA1withRSA";
      default:
        throw new IllegalArgumentException("Unknown algorithm: " + alg);
    }
  }

  static String jwsAlgorithmNameToJavaAlgorithmName(String alg) {
    switch (alg) {
      case "RS256":
        return "SHA256withRSA";
    }
    throw new IllegalArgumentException("Unknown algorithm: " + alg);
  }
}
