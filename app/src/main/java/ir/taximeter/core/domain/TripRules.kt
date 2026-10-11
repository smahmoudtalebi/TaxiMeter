package ir.taximeter.core.domain

import ir.taximeter.core.data.PassengerTripEntity
import java.util.UUID

object TripRules {
    const val MAX_PASSENGERS = 4

    fun newTripId(): String = UUID.randomUUID().toString()

    fun validateBoarding(
        seatNumber: Int,
        activeTrips: List<PassengerTripEntity>
    ): BoardingValidation {
        if (seatNumber !in 1..MAX_PASSENGERS) return BoardingValidation.InvalidSeat
        if (activeTrips.any { it.seatNumber == seatNumber && it.status == "ACTIVE" }) {
            return BoardingValidation.SeatOccupied
        }
        if (activeTrips.count { it.status == "ACTIVE" } >= MAX_PASSENGERS) {
            return BoardingValidation.VehicleFull
        }
        return BoardingValidation.Allowed
    }

    fun validateFareAdjustment(newFareToman: Long, reason: String): String? {
        if (newFareToman < 0) return "مبلغ کرایه نمی‌تواند منفی باشد."
        if (reason.isBlank()) return "برای اصلاح کرایه، ثبت علت الزامی است."
        return null
    }
}

sealed interface BoardingValidation {
    data object Allowed : BoardingValidation
    data object InvalidSeat : BoardingValidation
    data object SeatOccupied : BoardingValidation
    data object VehicleFull : BoardingValidation
}
