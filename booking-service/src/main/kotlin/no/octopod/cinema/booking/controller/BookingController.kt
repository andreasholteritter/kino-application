package no.octopod.cinema.booking.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import no.octopod.cinema.booking.converter.OrderConverter
import no.octopod.cinema.common.dto.OrderDto
import no.octopod.cinema.common.dto.SeatDto
import no.octopod.cinema.common.dto.SendOrderDto
import no.octopod.cinema.booking.entity.OrderEntity
import no.octopod.cinema.booking.entity.SeatReservationEntity
import no.octopod.cinema.booking.repository.OrderRepository
import no.octopod.cinema.booking.repository.SeatReservationRepository
import no.octopod.cinema.common.dto.TicketDto
import no.octopod.cinema.common.dto.WrappedResponse
import no.octopod.cinema.common.hateos.HalLink
import no.octopod.cinema.common.hateos.HalPage
import no.octopod.cinema.common.utility.SecurityUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.netflix.ribbon.RibbonClient
import org.springframework.http.*
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.lang.Exception
import java.net.URI
import java.time.ZonedDateTime
import java.util.*

@Api(value = "orders", description = "Place Orders & Reserve Seats")
@RequestMapping(
        path = ["/orders"],
        produces = [(MediaType.APPLICATION_JSON_VALUE)]
)
@RestController
class BookingController {
    companion object {
        const val MAX_RESERVATIONS_PER_USER = 5
    }

    @Autowired private lateinit var seatReserverationRepo: SeatReservationRepository
    @Autowired private lateinit var orderRepo: OrderRepository

    // Get admin user and password, used for system calls to sensistive api's
    @Value("\${systemUser}") private lateinit var systemUser: String
    @Value("\${systemPwd}") private lateinit var systemPwd: String
    @Autowired lateinit var client: RestTemplate


    @PostMapping(path = ["/reserve"])
    @ApiOperation(value = "Toggle reservation",
            notes = "Toggles the reservation of a specific seat for a specific screening")
    fun toggleSeatReservation(
            @ApiParam(name = "Seat and Screening",
                    value = "An object containing the seat to reserve, and which screening to reserve the seat for.")
            @RequestBody seatDto: SeatDto,
            authentication: Authentication
    ): ResponseEntity<Void> {
        if (seatDto.seat.isNullOrEmpty() || seatDto.screening_id?.toLongOrNull() == null) {
            return ResponseEntity.status(400).build()
        }

        val userId = SecurityUtil.getUserId(authentication)

        val reservedSeat = seatReserverationRepo.findBySeatAndScreeningId(seatDto.seat!!, seatDto.screening_id!!.toLong()).orElse(null)
        if (reservedSeat == null) {
            // If the seat is not reserved, and the user has not reached their limit, reserve the seat for them
            val currentUserReservations =
                    seatReserverationRepo.countByUserIdAndScreeningId(userId, seatDto.screening_id!!.toLong())

            if (currentUserReservations >= MAX_RESERVATIONS_PER_USER) {
                return ResponseEntity.status(400).build()
            }

            // remove from seats in show dto first
            try {
                val statusCode = client.exchange(
                        "http://kino-service/shows/${seatDto.screening_id}/seats/${seatDto.seat}",
                        HttpMethod.DELETE,
                        getSystemAuthorizationHeader(),
                        Any::class.java
                ).statusCodeValue

                // if the removal was successful, save the reservation
                if (statusCode == 204) {
                    seatReserverationRepo.save(
                            SeatReservationEntity(
                                    seat = seatDto.seat,
                                    userId = userId,
                                    reservationEndTime = ZonedDateTime.now().plusMinutes(5),
                                    screeningId = seatDto.screening_id!!.toLong()
                            )
                    )
                }
            } catch (e: HttpClientErrorException) {
                return ResponseEntity.status(404).build()
            }
            return ResponseEntity.status(204).build()
        } else if (reservedSeat.userId == userId) {
            // add back to seats, since the seat was already reserved by this user
            try {
                val statusCode = client.exchange(
                        "http://kino-service/shows/${seatDto.screening_id}/seats/${seatDto.seat}",
                        HttpMethod.POST,
                        getSystemAuthorizationHeader(),
                        Any::class.java
                ).statusCodeValue

               if (statusCode == 204) {
                    seatReserverationRepo.delete(reservedSeat)
                    return ResponseEntity.status(204).build()
                }
            } catch (e: Exception) {
                return ResponseEntity.status(400).build()
            }
        } else if (reservedSeat.userId != null && reservedSeat.userId != userId) {
            // this seat does not exist anymore
            return ResponseEntity.status(404).build()
        }

        /**
         * TODO:
         * Reserving seat by adding,
         * Purge reservations: https://www.callicoder.com/spring-boot-quartz-scheduler-email-scheduling-example/
         */
        return ResponseEntity.status(400).build()
    }

    @PostMapping
    @ApiOperation(value = "Create a successfully paid for order")
    fun createOrder(
            @ApiParam(name = "Purchase Object", value = "An object to represent a paid for purchase, used to create the tickets")
            @RequestBody sendOrderDto: SendOrderDto,
            authentication: Authentication
    ) : ResponseEntity<Void> {
        if (sendOrderDto.payment_token.isNullOrEmpty() ||
                sendOrderDto.screening_id?.toLongOrNull() == null ||
                sendOrderDto.seats == null) {
            return ResponseEntity.status(400).build()
        }

        val ticketIds: MutableList<Long> = mutableListOf()
        var success = true
        val mapper = ObjectMapper()

        // Create the tickets
        for (seat in sendOrderDto.seats!!) {
            val seatIsReservedByUser = seatReserverationRepo.existsByUserIdAndScreeningIdAndSeat(
                    userId = SecurityUtil.getUserId(authentication),
                    screeningId = sendOrderDto.screening_id!!.toLong(),
                    seat = seat
            )
            if (!seatIsReservedByUser) {
                // seat wasnt reserved by this user
                success = false
                break
            }

            val ticket = TicketDto(
                    userId = SecurityUtil.getUserId(authentication),
                    screeningId = sendOrderDto.screening_id,
                    seat = seat
            )

            val exchangeResponse = client.exchange(
                    "http://ticket-service/tickets",
                    HttpMethod.POST,
                    getSystemAuthorizationHeader(mapper.writeValueAsString(ticket)),
                    Any::class.java
            )

            val ticketId = exchangeResponse.headers.location?.toString()?.split("/")?.get(2)
            if (ticketId?.toLongOrNull() != null) {
                ticketIds.add(ticketId.toLong())
            }

            if (exchangeResponse.statusCodeValue != 201) {
                success = false
            }
        }


        seatReserverationRepo.deleteAllByUserIdAndScreeningId(
                userId = SecurityUtil.getUserId(authentication),
                screeningId = sendOrderDto.screening_id!!.toLong()
        )

        // revert the created tickets and refund the user
        if (!success) {
            for (ticketId in ticketIds) {
                try {
                    client.exchange(
                            "http://ticket-service/tickets/$ticketId",
                            HttpMethod.DELETE,
                            getSystemAuthorizationHeader(),
                            Any::class.java
                    )
                } catch (e: Exception) {
                    // dont do anything intentionally
                }
            }
            return ResponseEntity.status(400).build()
        }

        val orderEntity = OrderEntity(
                orderTime = ZonedDateTime.now().withFixedOffsetZone().withNano(0),
                userId = SecurityUtil.getUserId(authentication),
                screeningId = sendOrderDto.screening_id!!.toLong(),
                price = ticketIds.size * 225,
                tickets = ticketIds,
                paymentToken = "2222asdf4092735lkj"
        )

        val saved = orderRepo.save(orderEntity)
        return ResponseEntity.created(URI.create("/orders/${saved.id}")).build()
    }

    @GetMapping(path = ["/{orderId}"])
    @ApiOperation(value = "Get an order by id")
    fun getById(
            @ApiParam("The order ID")
            @PathVariable("orderId")
            orderId: Long,
            authentication: Authentication
    ): ResponseEntity<WrappedResponse<OrderDto>> {

        val orderEntity = orderRepo.findById(orderId).orElse(null)
        if (!SecurityUtil.isAuthenticatedOrAdmin(authentication, orderEntity.userId)) {
            return ResponseEntity.status(403).build()
        }

        if (orderEntity == null) {
            return ResponseEntity.status(404).build()
        }

        val dto = OrderConverter.transform(orderEntity)

        return ResponseEntity.ok(WrappedResponse(
                code = 200,
                data = dto
        ).validated())
    }

    @GetMapping
    @ApiOperation("Retrieve all the orders")
    fun getAll(
            @RequestParam("page", defaultValue = "1")
            page: Int,
            @RequestParam("limit", defaultValue = "10")
            limit: Int,
            @RequestParam("userId", required = false)
            userId: String?,
            authentication: Authentication
    ): ResponseEntity<WrappedResponse<HalPage<OrderDto>>> {
        // return everything for admin, but if user, only the ones belonging to them
        if (!SecurityUtil.isAuthenticatedOrAdmin(authentication, userId)) {
            return ResponseEntity.status(403).build()
        }

        if (page < 1 || limit < 1) {
            return ResponseEntity.status(400).body(
                    WrappedResponse<HalPage<OrderDto>>(
                            code = 400,
                            message = "Malformed page or limit supplied"
                    ).validated()
            )
        }

        val entityList: List<OrderEntity> = if (!userId.isNullOrEmpty()) {
            orderRepo.findAllByUserId(userId!!).toList()
        } else {
            orderRepo.findAll().toList()
        }

        val dto = OrderConverter.transform(
                entities = entityList,
                page = page,
                limit = limit
        )

        val uriBuilder = UriComponentsBuilder.fromPath("/orders")
        dto._self = HalLink(uriBuilder.cloneBuilder()
                .queryParam("userId", userId)
                .queryParam("page", page)
                .queryParam("limit", limit)
                .build().toString())

        if (!entityList.isEmpty() && page > 1) {
            dto.previous = HalLink(uriBuilder.cloneBuilder()
                    .queryParam("userId", userId)
                    .queryParam("page", page - 1)
                    .queryParam("limit", limit)
                    .build().toString())
        }

        if (((page) * limit) < entityList.size) {
            dto.next = HalLink(uriBuilder.cloneBuilder()
                    .queryParam("userId", userId)
                    .queryParam("page", page + 1)
                    .queryParam("limit", limit)
                    .build().toString())
        }

        return ResponseEntity.ok(
                WrappedResponse(
                        code = 200,
                        data = dto
                ).validated()
        )
    }

    // For doing admin posts to ticket and show service
    private fun getSystemAuthorizationHeader(body: String? = null): HttpEntity<Any> {
        val headers = HttpHeaders()
        val auth = "$systemUser:$systemPwd"
        val authBytes = auth.toByteArray()
        val encodedAuth = Base64.getEncoder().encode(authBytes)
        val base64auth = String(encodedAuth)
        val authHeader = "Basic $base64auth"
        headers.add("Authorization", authHeader)
        headers.add("User-Agent", "BookingService/System 0.0.1")
        if (body != null) {
            headers.contentType = MediaType.APPLICATION_JSON
        }
        return HttpEntity<Any>(body, headers)
    }
}