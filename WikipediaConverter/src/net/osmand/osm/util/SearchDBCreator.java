package net.osmand.osm.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.logging.Log;

import net.osmand.PlatformUtil;
import net.osmand.data.preparation.DBDialect;

public class SearchDBCreator {
	
	private static final Log log = PlatformUtil.getLog(SearchDBCreator.class);

	public static void main(String[] args) throws SQLException {
		String pathTodb = "/home/paul/osmand/wikivoyage/full_wikivoyage.sqlite";
		if(args.length > 0) {
			pathTodb = args[0];
		}
		DBDialect dialect = DBDialect.SQLITE;
		Connection conn = (Connection) dialect.getDatabaseConnection(pathTodb, log);
		conn.createStatement().execute("DROP TABLE IF EXISTS wikivoyage_search;");
		conn.createStatement().execute("CREATE TABLE wikivoyage_search(word text, translation_id long, article_title text, lang text)");
		conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_word ON wikivoyage_search(word);");
		PreparedStatement ps = conn.prepareStatement("INSERT INTO wikivoyage_search VALUES (?, ?, ?, ?)");
		PreparedStatement data = conn.prepareStatement("SELECT title, generated_id, lang FROM wikivoyage_articles");
		ResultSet rs = data.executeQuery();
		int batch = 0;
		while (rs.next()) {
			String title = rs.getString("title");
			long id = rs.getLong("generated_id");
			for (String s : title.split(" ")) {
				ps.setString(1, s.toLowerCase());
				ps.setLong(2, id);
				ps.setString(3, title);
				ps.setString(4, rs.getString("lang"));
				ps.addBatch();
				if (batch++ > 500) {
					ps.executeBatch();
					batch = 0;
				}
			}
		}
		ps.addBatch();
		ps.executeBatch();
		ps.close();
		data.close();
		rs.close();
		conn.close();
	}
}
