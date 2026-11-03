package io.github.jasondorjeshort.dystopo.identity

/**
 * The parts of an X.509 certificate (RFC 5280 4.1) the attestation policy reads, parsed by our own DER reader so the
 * policy runs in commonMain. Signatures are checked through a [Verifier]; nothing here trusts anything.
 */
class X509Cert private constructor(
    /** The whole certificate as given. */
    val encoded: ByteArray,
    /** The signed part (TBSCertificate), exactly as encoded. */
    val tbs: ByteArray,
    /** The outer signature algorithm's OID (equal to the TBS one, checked). */
    val signatureAlgorithm: String,
    val signature: ByteArray,
    /** The serial's magnitude, leading zeros dropped. */
    val serial: ByteArray,
    val issuer: ByteArray,
    val subject: ByteArray,
    val notBefore: Long,
    val notAfter: Long,
    /** The SubjectPublicKeyInfo, DER, as Java's `X509EncodedKeySpec` and the root list take it. */
    val publicKey: ByteArray,
    val extensions: List<Extension>,
) {
    class Extension(val oid: String, val critical: Boolean, val value: ByteArray)

    /** The serial as lowercase hex without leading zeros, the revocation list's form. */
    val serialHex: String get() = serial.hex().trimStart('0').ifEmpty { "0" }

    fun extension(oid: String): Extension? = extensions.firstOrNull { it.oid == oid }

    companion object {
        /** Largest certificate read; attestation certificates are 0.5 to 2 KiB, the RSA roots about 1.3 KiB. */
        const val MAX_SIZE = 16 * 1024

        const val MAX_EXTENSIONS = 32

        fun parse(der: ByteArray): X509Cert {
            if (der.size > MAX_SIZE) malformed("certificate of ${der.size} bytes, at most $MAX_SIZE")
            val cert = DerReader.single(der, "certificate").expectSequence("certificate")
            val parts = cert.reader()
            val tbsEl = parts.next().expectSequence("tbsCertificate")
            val outerAlg = algorithm(parts.next(), "signatureAlgorithm")
            val signature = parts.next().asBitStringOctets("signatureValue")
            parts.finish("certificate")

            val t = tbsEl.reader()
            var el = t.next()
            if (el.isContext(0) && el.constructed) {
                val version = DerReader.single(el.content(), "version").asLong("version")
                if (version !in 0L..2L) malformed("version $version")
                el = t.next()
            }
            val serial = el.asUnsignedBytes("serialNumber")
            val innerAlg = algorithm(t.next(), "signature")
            if (innerAlg != outerAlg) malformed("signature algorithms differ ($innerAlg, $outerAlg)")
            val issuer = t.next().expectSequence("issuer").encoded()
            val validity = t.next().expectSequence("validity").reader()
            val notBefore = validity.next().asTime("notBefore")
            val notAfter = validity.next().asTime("notAfter")
            validity.finish("validity")
            val subject = t.next().expectSequence("subject").encoded()
            val spki = t.next().expectSequence("subjectPublicKeyInfo").encoded()
            val extensions = ArrayList<Extension>()
            while (t.hasMore) {
                val opt = t.next()
                when {
                    opt.isContext(1) || opt.isContext(2) -> Unit // unique identifiers, unused
                    opt.isContext(3) && opt.constructed -> {
                        val list = opt.reader().next().expectSequence("extensions")
                        for (ext in list.children()) {
                            if (extensions.size >= MAX_EXTENSIONS) malformed("more than $MAX_EXTENSIONS extensions")
                            val f = ext.expectSequence("extension").reader()
                            val oid = f.next().asOid("extnID")
                            var v = f.next()
                            var critical = false
                            if (v.isUniversal(DerElement.BOOLEAN)) {
                                critical = v.asBoolean("critical")
                                v = f.next()
                            }
                            val value = v.asOctets("extnValue")
                            f.finish("extension")
                            if (extensions.any { it.oid == oid }) malformed("extension $oid twice in one certificate")
                            extensions += Extension(oid, critical, value)
                        }
                    }
                    else -> malformed("unexpected ${opt.describe()} in tbsCertificate")
                }
            }
            return X509Cert(der, tbsEl.encoded(), outerAlg, signature, serial, issuer, subject, notBefore, notAfter, spki, extensions)
        }

        private fun algorithm(e: DerElement, what: String): String {
            val r = e.expectSequence(what).reader()
            val oid = r.next().asOid(what)
            // Parameters: absent or NULL for the algorithms Google's chains use; anything else is read and ignored.
            if (r.hasMore) r.next()
            r.finish(what)
            return oid
        }
    }
}
