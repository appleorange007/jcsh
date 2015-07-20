/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/*
Copyright (c) 2002,2003,2004,2005,2006 ymnk, JCraft,Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.

  2. Redistributions in binary form must reproduce the above copyright 
     notice, this list of conditions and the following disclaimer in 
     the documentation and/or other materials provided with the distribution.

  3. The names of the authors may not be used to endorse or promote products
     derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT,
INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.jcraft.jsch;

class UserAuthKeyboardInteractive extends UserAuth{
  public boolean start(Session session, UserInfo userinfo) throws Exception{
    this.userinfo=userinfo;
    if(!(userinfo instanceof UIKeyboardInteractive)){
      return false;
    }

    Packet packet=session.packet;
    Buffer buf=session.buf;
    final String username=session.username;
    String dest=username+"@"+session.host;
    if(session.port!=22){
      dest+=(":"+session.port);
    }
    byte[] password=session.password;

    boolean cancel=false;

    byte[] _username=null;
    _username=Util.str2byte(username);

    while(true){
      // send
      // byte      SSH_MSG_USERAUTH_REQUEST(50)
      // string    user name (ISO-10646 UTF-8, as defined in [RFC-2279])
      // string    service name (US-ASCII) "ssh-userauth" ? "ssh-connection"
      // string    "keyboard-interactive" (US-ASCII)
      // string    language tag (as defined in [RFC-3066])
      // string    submethods (ISO-10646 UTF-8)
      packet.reset();
      buf.putByte((byte)SSH_MSG_USERAUTH_REQUEST);
      buf.putString(_username);
      buf.putString("ssh-connection".getBytes());
      //buf.putString("ssh-userauth".getBytes());
      buf.putString("keyboard-interactive".getBytes());
      buf.putString("".getBytes());
      buf.putString("".getBytes());
      session.write(packet);

      boolean firsttime=true;
      loop:
      while(true){
//	try{  buf=session.read(buf); }
//	catch(JSchException e){
//	  return false;
//	}
//	catch(java.io.IOException e){
//	  return false;
//	}

        buf=session.read(buf);

        //System.err.println("read: 52 ? "+    buf.buffer[5]);

	if(buf.buffer[5]==SSH_MSG_USERAUTH_SUCCESS){
	  return true;
	}
	if(buf.buffer[5]==SSH_MSG_USERAUTH_BANNER){
	  buf.getInt(); buf.getByte(); buf.getByte();
	  byte[] _message=buf.getString();
	  byte[] lang=buf.getString();
	  String message=null;
	  try{ message=new String(_message, "UTF-8"); }
	  catch(java.io.UnsupportedEncodingException e){
	    message=new String(_message);
	  }
	  if(userinfo!=null){
	    userinfo.showMessage(message);
	  }
	  continue loop;
	}
	if(buf.buffer[5]==SSH_MSG_USERAUTH_FAILURE){
	  buf.getInt(); buf.getByte(); buf.getByte(); 
	  byte[] foo=buf.getString();
	  int partial_success=buf.getByte();
//	  System.err.println(new String(foo)+
//			     " partial_success:"+(partial_success!=0));

	  if(partial_success!=0){
	    throw new JSchPartialAuthException(new String(foo));
	  }

	  if(firsttime){
	    return false;
	    //throw new JSchException("USERAUTH KI is not supported");
	    //cancel=true;  // ??
	  }
	  break;
	}
	if(buf.buffer[5]==SSH_MSG_USERAUTH_INFO_REQUEST){
	  firsttime=false;
	  buf.getInt(); buf.getByte(); buf.getByte();
	  String name=new String(buf.getString());
	  String instruction=new String(buf.getString());
	  String languate_tag=new String(buf.getString());
	  int num=buf.getInt();
//System.err.println("name: "+name);
//System.err.println("instruction: "+instruction);
//System.err.println("lang: "+languate_tag);
//System.err.println("num: "+num);
	  String[] prompt=new String[num];
	  boolean[] echo=new boolean[num];
	  for(int i=0; i<num; i++){
	    prompt[i]=new String(buf.getString());
	    echo[i]=(buf.getByte()!=0);
//System.err.println("  "+prompt[i]+","+echo[i]);
	  }

	  byte[][] response=null;
	  if(num>0
	     ||(name.length()>0 || instruction.length()>0)
	     ){
	    UIKeyboardInteractive kbi=(UIKeyboardInteractive)userinfo;
	    if(userinfo!=null){
              String[] _response=kbi.promptKeyboardInteractive(dest,
                                                               name,
                                                               instruction,
                                                               prompt,
                                                               echo);
              if(_response!=null){
                response=new byte[_response.length][];
                for(int i=0; i<_response.length; i++){
                  response[i]=Util.str2byte(_response[i]);
                }
              }
	    }
            else if(password!=null && 
                    prompt.length==1 &&
                    !echo[0] &&
                    prompt[0].toLowerCase().startsWith("password:")){
              response=new byte[1][];
              response[0]=password;
              password=null;
            }
	  }

	  // byte      SSH_MSG_USERAUTH_INFO_RESPONSE(61)
	  // int       num-responses
	  // string    response[1] (ISO-10646 UTF-8)
	  // ...
	  // string    response[num-responses] (ISO-10646 UTF-8)
//if(response!=null)
//System.err.println("response.length="+response.length);
//else
//System.err.println("response is null");
	  packet.reset();
	  buf.putByte((byte)SSH_MSG_USERAUTH_INFO_RESPONSE);
	  if(num>0 &&
	     (response==null ||  // cancel
	      num!=response.length)){

            if(response==null){  
              // working around the bug in OpenSSH ;-<
              buf.putInt(num);
              for(int i=0; i<num; i++){
                buf.putString("".getBytes());
              }
            }
            else{
              buf.putInt(0);
            }

	    if(response==null)
	      cancel=true;
	  }
	  else{
	    buf.putInt(num);
	    for(int i=0; i<num; i++){
//System.err.println("response: |"+new String(response[i])+"| <- replace here with **** if you need");
	      buf.putString(response[i]);
	    }
	  }
	  session.write(packet);
          /*
	  if(cancel)
	    break;
          */
	  continue loop;
	}
	//throw new JSchException("USERAUTH fail ("+buf.buffer[5]+")");
	return false;
      }
      if(cancel){
	throw new JSchAuthCancelException("keyboard-interactive");
	//break;
      }
    }
    //return false;
  }
}