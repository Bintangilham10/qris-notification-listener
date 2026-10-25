package id.ciganstore.qrislistener.data

interface PaymentRepository {
    fun insertIfNew(event: PaymentEvent): Boolean
    fun listNewest(limit: Int): List<PaymentEvent>

    /**
     * Clears local history. Implementations that deliver events to a server must keep events that
     * have not been delivered yet, so clearing history can never lose a payment.
     */
    fun clear()
}
