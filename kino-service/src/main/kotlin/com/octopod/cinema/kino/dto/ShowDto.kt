package com.octopod.cinema.kino.dto

import io.swagger.annotations.ApiModelProperty

data class ShowDto (

        @ApiModelProperty("The time when the show starts")
        var startTime: Int? = null,

        @ApiModelProperty("Name of the movie")
        var movie: String? = null,

        @ApiModelProperty("Id for the show")
        var id: String? = null
)