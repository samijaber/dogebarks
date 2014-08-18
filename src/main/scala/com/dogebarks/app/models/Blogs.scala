package com.dogebarks.app

import scala.slick.driver.H2Driver.simple._

class Blogs(tag: Tag) extends Table[(String, String, String, String, Long)](tag, "BLOGS") {
		def hashtag 		= column[String]("HASHTAG", O.PrimaryKey)
		def owner 			= column[String]("OWNER")
		def title				= column[String]("TITLE")
		def createdAt 	= column[String]("CREATEDAT")
		def lastId		 	= column[Long]("LASTUPDATED")
		def * 					= (hashtag, owner, title, createdAt, lastId)
}