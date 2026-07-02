// ============================================================
// KHH Attendance – Google Apps Script Web App
// Deploy as: Execute as Me, Access: Anyone
// ============================================================

var SPREADSHEET_ID = "158oP5h-grfDKEgiNrsF_Z8BaX5IIhl7p7zIqNS71-8s";
var SHEET_NAME     = "Sheet1";

// Column indices (1-based)
var COL_DATE        = 2;  // B
var COL_ID          = 3;  // C
var COL_NAME        = 4;  // D  (left blank for now)
var COL_CLOCK_IN    = 5;  // E
var COL_ANN_IN      = 6;  // F  Announcement(In)
var COL_LUNCH       = 7;  // G
var COL_CLOCK_OUT   = 8;  // H
var COL_ANN_OUT     = 9;  // I  Announcement(Out)

function doPost(e) {
  try {
    var data       = JSON.parse(e.postData.contents);
    var action     = data.action;      // CLOCK_IN | CLOCK_OUT | LUNCH | ACKNOWLEDGE
    var employeeId = String(data.employeeId || "");
    var date       = String(data.date  || "");      // yyyy-MM-dd
    var timestamp  = String(data.timestamp || "");  // HH:mm:ss

    if (!action || !employeeId || !date) {
      return respond({ status: "error", message: "Missing required fields" });
    }

    var ss    = SpreadsheetApp.openById(SPREADSHEET_ID);
    var sheet = ss.getSheetByName(SHEET_NAME);
    if (!sheet) {
      return respond({ status: "error", message: "Sheet not found: " + SHEET_NAME });
    }

    var row = findOrCreateRow(sheet, date, employeeId);

    // Write name to column D whenever it's provided (safe to overwrite with same value)
    if (data.name) {
      sheet.getRange(row, COL_NAME).setValue(data.name);
    }

    if (action === "CLOCK_IN") {
      sheet.getRange(row, COL_CLOCK_IN).setValue(timestamp);

    } else if (action === "CLOCK_OUT") {
      sheet.getRange(row, COL_CLOCK_OUT).setValue(timestamp);

    } else if (action === "LUNCH") {
      var hasLunch = data.hasLunch;
      sheet.getRange(row, COL_LUNCH).setValue(hasLunch ? "Yes" : "No");

    } else if (action === "ACKNOWLEDGE") {
      var col = (data.ackType === "CLOCK_OUT") ? COL_ANN_OUT : COL_ANN_IN;
      sheet.getRange(row, col).setValue("Acknowledged");

    } else {
      return respond({ status: "error", message: "Unknown action: " + action });
    }

    SpreadsheetApp.flush();
    return respond({ status: "ok", row: row, action: action });

  } catch (err) {
    return respond({ status: "error", message: err.toString() });
  }
}

// Returns the row index for the given date+employeeId, creating it if absent.
function findOrCreateRow(sheet, date, employeeId) {
  var lastRow = sheet.getLastRow();

  // Search existing rows (skip row 1 which is the header)
  if (lastRow >= 2) {
    var dateVals = sheet.getRange(2, COL_DATE, lastRow - 1, 1).getValues();
    var idVals   = sheet.getRange(2, COL_ID,   lastRow - 1, 1).getValues();
    for (var i = 0; i < dateVals.length; i++) {
      // Sheets may return the cell as a Date object — normalise to yyyy-MM-dd
      var cellDate = dateVals[i][0];
      var cellDateStr;
      if (cellDate instanceof Date) {
        var y = cellDate.getFullYear();
        var m = String(cellDate.getMonth() + 1).padStart(2, '0');
        var d = String(cellDate.getDate()).padStart(2, '0');
        cellDateStr = y + '-' + m + '-' + d;
      } else {
        cellDateStr = String(cellDate);
      }
      if (cellDateStr === date && String(idVals[i][0]) === employeeId) {
        return i + 2; // +2: array is 0-based, data starts at row 2
      }
    }
  }

  // Row not found — append a new one
  var newRow = Math.max(lastRow + 1, 2);
  // Store date as plain text to prevent Sheets from re-interpreting it
  sheet.getRange(newRow, COL_DATE).setNumberFormat('@').setValue(date);
  sheet.getRange(newRow, COL_ID).setValue(employeeId);
  // COL_NAME left blank intentionally
  return newRow;
}

function respond(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
