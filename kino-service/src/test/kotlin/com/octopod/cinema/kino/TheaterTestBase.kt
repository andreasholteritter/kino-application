package com.octopod.cinema.kino

import com.octopod.cinema.kino.dto.TheaterDto
import com.octopod.cinema.kino.repository.TheaterRepository
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Before
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.test.context.junit4.SpringRunner

@RunWith(SpringRunner::class)
@SpringBootTest(classes = [(TheaterApplication::class)],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class TheaterTestBase {

    @LocalServerPort private var port = 0

    @Autowired
    private lateinit var crud: TheaterRepository

    //Taken fram arcuri82 - NRv2 test
    @Before
    fun clean() {

        RestAssured.baseURI = "http://localhost"
        RestAssured.port = port
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()

        crud.deleteAll()
        /*
        val list = given().accept(ContentType.JSON).get("/theater")
                .then()
                .statusCode(200)
                .extract()
                .`as`(Array<TheaterDto>::class.java)
                .toList()

        list.stream().forEach {
            given().pathParam("id", it.id)
                    .delete("/{id}")
                    .then()
                    .statusCode(204)
        }

        given().get()
                .then()
                .statusCode(200)
                .body("size()", equalTo(0))
*/
    }
}