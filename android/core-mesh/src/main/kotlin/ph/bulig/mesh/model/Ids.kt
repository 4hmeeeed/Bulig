package ph.bulig.mesh.model

/**
 * Identifiers are minted on the device, never issued by the server.
 *
 * A phone with no connectivity cannot ask a server for an id, so the whole
 * offline-first architecture depends on the device owning identity. The server
 * accepts these values and enforces uniqueness on them.
 */
@JvmInline
value class PacketId(val value: String) {
    init {
        require(value.isNotBlank()) { "packet id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class EmergencyId(val value: String) {
    init {
        require(value.isNotBlank()) { "emergency id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "device id must not be blank" }
    }

    override fun toString(): String = value
}
