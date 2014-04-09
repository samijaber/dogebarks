require 'bundler/setup'
require 'sinatra'
require 'sinatra/activerecord'
require 'sinatra/flash'
require 'sinatra-websocket'
require 'omniauth-twitter'
require 'net/http'
require 'net/https'
require 'twitter'
require 'tweetstream'

@@splash_path = '/'
@@auth_path = '/auth/twitter'
@@app_path = '/app'
@@new_blog_path = '/blog/new'
@@blog_path = '/blog/'

#CONFIG
configure do
  enable :sessions
  set :database, 'sqlite3:///db/db.sqlite3'
  set :port, 80
  set :bind, '0.0.0.0'
  set :sockets, []
  use OmniAuth::Builder do
    provider :twitter, ENV['CONSUMER_KEY'], ENV['CONSUMER_SECRET']
  end
end

#HELPERS
helpers do
  def current_user
    !session[:uid].nil?
  end
end

#ROUTES
get '/auth/twitter/callback' do
  env['omniauth.auth'] ? session[:admin] = true : halt(401,'Not Authorized')
  session[:credentials] = env['omniauth.auth'][:credentials]
  session[:uid] = env['omniauth.auth'][:uid]
  session[:info] = env['omniauth.auth'][:info]
  redirect to(@@app_path)
end

get '/auth/failure' do
  params[:message]
end

get '/' do
  if !request.websocket?
    erb :socket
  else
    request.websocket do |ws|
      ws.onopen do
        settings.sockets << ws
      end
      ws.onmessage do |msg|
        EM.next_tick { settings.sockets.each{|s| s.send(msg) } }
      end
      ws.onclose do
        warn("websocket closed")
        settings.sockets.delete(ws)
      end
    end
  end
end

get '/app' do
  @blogs = Blog.all.sort_by!{|x| x.created_at}.reverse!
  erb :app
end

get '/blog/new' do
  redirect to(@@auth_path) unless current_user
  erb :blog_new
end

post '/blog/new' do
  redirect to(@@auth_path) unless current_user
  blog = Blog.new
  blog.title = params[:title]
  blog.hashtag = "#" + params[:hashtag].gsub("#","")
  blog.screen_name = session[:info][:nickname]
  blog.save
  redirect to(@@blog_path+blog.id.to_s)
end

get '/blog/:id' do
  redirect to(@@auth_path) unless current_user
  @blog = Blog.find params[:id]
  @permissions = @blog.permissions
  @tweets = []
  if @blog.updated_at < 1.mins.ago
    client = Twitter::REST::Client.new do |config|
      config.consumer_key        = ENV['CONSUMER_KEY']
      config.consumer_secret     = ENV['CONSUMER_SECRET']
      config.access_token        = session[:credentials][:token]
      config.access_token_secret = session[:credentials][:secret]
    end
    @tweets += client.search("from:" + @blog.screen_name + " " + @blog.hashtag).take(100).to_a #.select{|x| x.created_at >= @blog.created_at}
    @permissions.each do |permission|
      @tweets += client.search("from:" + permission.screen_name + " " + @blog.hashtag).take(100).to_a #.select{|x| x.created_at >= @blog.created_at}
    end
  else
  end
  @tweets.sort_by!{|x| x.created_at}
  @tweets.reverse!
  erb :blog_show
end

get '/blog/all' do
  @blog = Blog.all.sort_by!{|x| x.created_at}.reverse!
  erb :blog_all
end

post '/blog/:id/adduser' do
  redirect to(@@auth_path) unless current_user
  blog = Blog.find params[:id]
  redirect to(@@app_path) unless session[:info][:nickname] == blog.screen_name
  screenname = params[:screenname]
  Permission.create({:blog_id => params[:id], :screen_name => params[:screenname]})
  flash[:message] = "added user " + screenname
  redirect to(@@blog_path + params[:id])
end

get '/blog/:id/deluser/:screenname' do
  redirect to(@@auth_path) unless current_user
  blog = Blog.find params[:id]
  redirect to(@@app_path) unless session[:info][:nickname] == blog.screen_name
  screenname = params[:screenname]
  Permission.delete(Permission.where("blog_id = ? AND screen_name = ?", blog.id, screenname))
  flash[:message] = "deleted user " + screenname
  redirect to(@@blog_path + params[:id])
end

get '/tokens' do
  session[:credentials][:token] + " " + session[:credentials][:secret] + " " + ENV['CONSUMER_KEY'] + " " + ENV['CONSUMER_SECRET']
end

class Blog < ActiveRecord::Base
  has_and_belongs_to_many :saved_tweets
  has_many :permissions
end

class SavedTweet < ActiveRecord::Base
  has_and_belongs_to_many :blogs
end

class Permission < ActiveRecord::Base
  belongs_to :blog
end

Thread.new do
  sleep 5
  settings.sockets.each{|socket| socket.send "connected"}
  while true do 
    sleep 3
    settings.sockets.each{|socket| socket.send "tick"}
  end
end
