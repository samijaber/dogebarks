package com.dogebarks.app

import org.scalatra._
import scalate.ScalateSupport
import scala.collection.JavaConversions._
import scala.util.parsing.json._

import java.text.SimpleDateFormat
import java.util.Date

import org.scribe.model.Token

import twitter4j.{Twitter, TwitterFactory}
import twitter4j.conf._
import twitter4j._

import Schema._
import scala.slick.driver.H2Driver.simple._
import scala.slick.jdbc.JdbcBackend.Database.dynamicSession
import scala.slick.lifted.TableQuery._
import scala.slick.jdbc.meta._

case class DogeServlet(db: Database) extends DogebarksStack with ScalateSupport {
	val ddls = blogs.ddl ++ contributors.ddl ++ tweets.ddl
	val timeFormat = new SimpleDateFormat()

	object Helpers {
		def add_contributor(hashtag: String, user: String, since: String) = {
			val twitter: Twitter = new TwitterFactory (new ConfigurationBuilder()
				  .setOAuthConsumerKey(Secret.apiKey)
				  .setOAuthConsumerSecret(Secret.apiSecret)
				  .setOAuthAccessToken(SessionUser.accTkn.get.getToken)
				  .setOAuthAccessTokenSecret(SessionUser.accTkn.get.getSecret)
				  .build)
				.getInstance


			val query: twitter4j.Query = new twitter4j.Query("#" + hashtag + " from:" + user);
			val result: QueryResult = twitter.search(query);

			db withDynSession {
		  //   val q1 = for { b <- blogs if b.hashtag === hashtag } yield b.refreshURL
				// var query: twitter4j.Query = if (q1.first.isEmpty)
				// 	new twitter4j.Query("#" + hashtag + " from:" + user);
	   //  	else
	   //  		new twitter4j.Query("#" + hashtag + " from:" + user + q1.first.get);

				// val result: QueryResult = twitter.search(query);

				val time = timeFormat.format(new Date())
				contributors += (user, hashtag, time)
				contributors.insertStatement
				contributors.insertInvoker
				
		    for (status <- result.getTweets()) {
		    	val date = timeFormat.format(status.getCreatedAt())
		    	val mediaUrl = {
		    		val mediaArr = status.getMediaEntities()
		    		if (mediaArr.isEmpty)
		    			None
	    			else
							Some(mediaArr(0).getMediaURL())
		    	}
		    	tweets += (status.getId().toString(), status.getText(), status.getUser().getScreenName(), hashtag, date, mediaUrl)
		    }
			}
		}

		def get_username(): Option[String] = {
			try { 
				val respBody = TwitterOAuth.get("https://api.twitter.com/1.1/account/verify_credentials.json", SessionUser.accTkn.get)
				JSON.parseFull(respBody) match  {
					case Some(m:Map[String,Any]) => Some(m("screen_name").toString())
					case _ => None
				}
			} catch {
			  case e: Exception => None
			}
		}

		def auth = if (SessionUser.accTkn.isEmpty) redirect("/login")

		def shutdown = db withDynSession {
			ddls.drop
		}
	}
	import Helpers._

	before() {
		contentType="text/html"

		db withDynSession {
			if (MTable.getTables.list().isEmpty)
				ddls.create
		}

		//TODO: Add name to templateAttribute map in DogebarksStack
		if (!SessionUser.name.isEmpty)
			templateAttributes("name") = SessionUser.name.get
		else
			templateAttributes("name") = "default"

	}

	before("/main*") {
		auth
	}

	get("/") {
		auth
		redirect("/main")
	}

	get("/main") {
		db withDynSession {
			val q = for {
				b <- blogs if b.owner === SessionUser.name.get
			} yield b

			ssp("/main", "name" -> SessionUser.name.get, "blogs" -> q.list())
		}
	}

	get("/main/new_blog") {
		val time = timeFormat.format(new Date())
		db withDynSession {
			blogs += (params("hashtag"), SessionUser.name.get, params("title"), time, None)
			blogs.insertStatement
			blogs.insertInvoker
		}

		Helpers.add_contributor(params("hashtag"), SessionUser.name.get, time)
		redirect("/main/blog/" + params("hashtag"))
	}

	get("/main/blog/:id") {
		db withDynSession {
			//get last blog update time
			val q1 = for {
				b <- blogs if b.hashtag === params("id")
			} yield b
			val q1Arr = q1.list()

			//get all users 
			val q2 = for {
				u <- contributors if u.hashtag === params("id")
			} yield u
			for (users <- q2.list()) {
				// Helpers.add_contributor(params("id"), users._1, "")
			}

			//Retrieve saved tweets with this hashtag ranked by date
			val q3 = for {
				t <- tweets if t.hashtag === params("id")
			} yield t
			ssp("/blog", "title" -> params("id"), "tweets" -> q3.list())
		}
	}

	get("/main/blog/new_contributor") {
		val time = timeFormat.format(new Date())
		Helpers.add_contributor(params("hashtag"), params("user"), time)
		redirect("/main/blog/" + params("hashtag"))
	}

	//=====
	//OAuth
	//=====
	object SessionUser {
		var accTkn: Option[Token] = None
		var name: Option[String] = None		
	}

	get("/logout") {
		SessionUser.accTkn = None
		redirect("/main")
	}

	get("/login") {
		ssp("/login")
	}

	get("/auth") {
		val token: Token = TwitterOAuth.requestToken()
	  val authUrl = TwitterOAuth.getAuthUrl(token)
	  redirect(authUrl)
	}

	get("/auth/callback") {
		val requestToken: Token = new Token(params("oauth_token"), Secret.apiSecret)
		SessionUser.accTkn = Some(TwitterOAuth.getAccessToken(requestToken, params("oauth_verifier")))
		SessionUser.name = get_username
		redirect("/")
	}
}