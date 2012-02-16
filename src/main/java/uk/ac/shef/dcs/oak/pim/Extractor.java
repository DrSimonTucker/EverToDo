package uk.ac.shef.dcs.oak.pim;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.THttpClient;

import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.notestore.NoteList;
import com.evernote.edam.notestore.NoteStore;
import com.evernote.edam.type.Note;
import com.evernote.edam.type.NoteSortOrder;
import com.evernote.edam.type.Notebook;
import com.evernote.edam.type.User;
import com.evernote.edam.userstore.AuthenticationResult;
import com.evernote.edam.userstore.UserStore;

/**
 * Hello world!
 * 
 */
public class Extractor
{
   private static final String consumerKey = "brotherlogic";
   private static final String consumerSecret = "d7f5addaa079a592";

   private static final String evernoteHost = "sandbox.evernote.com";
   private static final String userStoreUrl = "https://" + evernoteHost + "/edam/user";
   private static final String noteStoreUrlBase = "https://" + evernoteHost + "/edam/note/";

   private static final String userAgent = "Evernote/evertodo (Java) 0.1";

   private UserStore.Client userStore;
   private NoteStore.Client noteStore;
   private String authToken;

   public static void main(String[] args) throws Exception
   {
      Extractor ex = new Extractor();
      ex.listNotebooks();
   }

   public void listNotebooks() throws Exception
   {
      BufferedReader reader = new BufferedReader(new FileReader("passfile"));
      String[] elems = reader.readLine().trim().split("\\s+");
      connect(elems[0], elems[1]);
      reader.close();
      List<Notebook> notebooks = noteStore.listNotebooks(authToken);
      for (Notebook notebook : notebooks)
         procNotebook(notebook);
   }

   private void procNotebook(Notebook nb) throws Exception
   {
      NoteFilter filter = new NoteFilter();
      filter.setNotebookGuid(nb.getGuid());
      filter.setOrder(NoteSortOrder.CREATED.getValue());
      filter.setAscending(true);

      NoteList list = noteStore.findNotes(authToken, filter, 0, 100);
      List<Note> notes = list.getNotes();
      for (Note note : notes)
      {
         Note aNote = noteStore.getNote(authToken, note.getGuid(), true, false, false, false);
         procContent(note.getTitle(), aNote.getContent());
      }
   }

   Pattern todoPat = Pattern.compile("<div><en-todo></en-todo>(.*?)</div>");

   private void procContent(String name, String text)
   {
      boolean pName = false;
      Matcher matcher = todoPat.matcher(text);
      while (matcher.find())
      {
         if (!pName)
         {
            System.out.println(name);
            pName = true;
         }
         procTodo(matcher.group(1));
      }
   }

   private void procTodo(String todoText)
   {
      System.out.println("  " + todoText);
   }

   private boolean connect(String username, String password) throws Exception
   {
      THttpClient userStoreTrans = new THttpClient(userStoreUrl);
      userStoreTrans.setCustomHeader("User-Agent", userAgent);
      TBinaryProtocol userStoreProt = new TBinaryProtocol(userStoreTrans);
      userStore = new UserStore.Client(userStoreProt, userStoreProt);

      // Check that we can talk to the server
      boolean versionOk = userStore.checkVersion("Evernote evertodo (Java)",
            com.evernote.edam.userstore.Constants.EDAM_VERSION_MAJOR,
            com.evernote.edam.userstore.Constants.EDAM_VERSION_MINOR);
      if (!versionOk)
      {
         System.err.println("Incomatible EDAM client protocol version");
         return false;
      }

      // Authenticate using username & password
      AuthenticationResult authResult = null;
      try
      {
         authResult = userStore.authenticate(username, password, consumerKey, consumerSecret);
      }
      catch (EDAMUserException ex)
      {
         // Note that the error handling here is far more detailed than you
         // would
         // provide to a real user. It is intended to give you an idea of why
         // the
         // sample application isn't able to authenticate to our servers.

         // Any time that you contact us about a problem with an Evernote API,
         // please provide us with the exception parameter and errorcode.
         String parameter = ex.getParameter();
         EDAMErrorCode errorCode = ex.getErrorCode();

         System.err.println("Authentication failed (parameter: " + parameter + " errorCode: "
               + errorCode + ")");
         if (errorCode == EDAMErrorCode.INVALID_AUTH)
         {
            if (parameter.equals("consumerKey"))
            {
               if (consumerKey.equals("en-edamtest"))
               {
                  System.err
                        .println("You must replace the variables consumerKey and consumerSecret with the values you received from Evernote.");
               }
               else
               {
                  System.err.println("Your consumer key was not accepted by " + evernoteHost);
                  System.err
                        .println("This sample client application requires a client API key. If you requested a web service API key, you must authenticate using OAuth as shown in sample/java/oauth");
               }
               System.err
                     .println("If you do not have an API Key from Evernote, you can request one from http://www.evernote.com/about/developer/api");
            }
            else if (parameter.equals("username"))
            {
               System.err.println("You must authenticate using a username and password from "
                     + evernoteHost);
               if (evernoteHost.equals("www.evernote.com") == false)
               {
                  System.err.println("Note that your production Evernote account will not work on "
                        + evernoteHost + ",");
                  System.err.println("you must register for a separate test account at https://"
                        + evernoteHost + "/Registration.action");
               }
            }
            else if (parameter.equals("password"))
            {
               System.err.println("The password that you entered is incorrect");
            }
         }
         return false;
      }

      // The result of a succesful authentication is an opaque authentication
      // token
      // that you will use in all subsequent API calls. If you are developing a
      // web application that authenticates using OAuth, the OAuth access token
      // that you receive would be used as the authToken in subsquent calls.
      authToken = authResult.getAuthenticationToken();

      // The Evernote NoteStore allows you to accessa user's notes.
      // In order to access the NoteStore for a given user, you need to know the
      // logical "shard" that their notes are stored on. The shard ID is
      // included
      // in the URL used to access the NoteStore.
      User user = authResult.getUser();
      String shardId = user.getShardId();

      // System.out.println("Successfully authenticated as " +
      // user.getUsername());

      // Set up the NoteStore client
      String noteStoreUrl = noteStoreUrlBase + shardId;
      THttpClient noteStoreTrans = new THttpClient(noteStoreUrl);
      noteStoreTrans.setCustomHeader("User-Agent", userAgent);
      TBinaryProtocol noteStoreProt = new TBinaryProtocol(noteStoreTrans);
      noteStore = new NoteStore.Client(noteStoreProt, noteStoreProt);

      return true;
   }
}
