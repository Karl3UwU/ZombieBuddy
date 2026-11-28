local hasShownNotification = false

if KRLZomBud and KRLZomBud.LogOverlay then
    KRLZomBud.LogOverlay.addFilter("[CleanUI] Skipping invalid")
    KRLZomBud.LogOverlay.addFilter("\" overrides ")
    KRLZomBud.LogOverlay.addFilter("action was null")
    KRLZomBud.LogOverlay.addFilter("ATA2Tuning")
    KRLZomBud.LogOverlay.addFilter("can't find map objects file")
    KRLZomBud.LogOverlay.addFilter("can't find mod.info in mod dir")
    KRLZomBud.LogOverlay.addFilter("feedingTrough")
    KRLZomBud.LogOverlay.addFilter("fmod: FMOD")
    KRLZomBud.LogOverlay.addFilter("ignoring invalid ItemPicker")
    KRLZomBud.LogOverlay.addFilter("IngameState.")
    KRLZomBud.LogOverlay.addFilter("ISExpBar")
    KRLZomBud.LogOverlay.addFilter("MainScreenState.")
    KRLZomBud.LogOverlay.addFilter("no room or building")
    KRLZomBud.LogOverlay.addFilter("no such mesh")
    KRLZomBud.LogOverlay.addFilter("no such sound")
    KRLZomBud.LogOverlay.addFilter("no such texture")
    KRLZomBud.LogOverlay.addFilter("not found in ZONE_MAP")
    KRLZomBud.LogOverlay.addFilter("OreVein")
    KRLZomBud.LogOverlay.addFilter("recursive require")
    KRLZomBud.LogOverlay.addFilter("require(\"")
    KRLZomBud.LogOverlay.addFilter("setGameSpeed")
    KRLZomBud.LogOverlay.addFilter("skinningData is null")
    KRLZomBud.LogOverlay.addFilter("TraitZ ")
end

local function checkKRLZomBudInstallation()
    -- Only check once
    if hasShownNotification then
        return
    end
    
    if KRLZomBud and KRLZomBud.getVersion then
        print("[KRLZomBud] KRLZomBud.getVersion() = " .. KRLZomBud.getVersion())
        -- KRLZomBud is properly installed
        return
    end
    if not KRLZomBud then
        print("[KRLZomBud] KRLZomBud global is nil.")
    else
        print("[KRLZomBud] KRLZomBud.getVersion is nil.")
    end
    print("[KRLZomBud] showing installation notification.")
    
    -- KRLZomBud is not installed - show notification
    hasShownNotification = true
    
    local function showInstallationDialog()
        local core = getCore()
        if not core then
            return
        end
        
        -- Determine the destination directory and screenshot image based on OS
        local dstDir = ""
        local screenshotImage = ""
        if isSystemMacOS() then
            dstDir = "~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/"
            screenshotImage = "media/ui/zb_steam_options_osx.png"
        elseif isSystemWindows() then
            -- Windows path - adjust based on your Steam installation
            dstDir = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid\\"
            screenshotImage = "media/ui/zb_steam_options_win.png"
        else
            -- Linux path
            dstDir = "~/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/"
            screenshotImage = "media/ui/zb_steam_options_osx.png"
        end
        
        -- Get the mod directory to find JAR files
        local modInfo = getModInfoByID("KRLZomBud")
        local modDir = modInfo and modInfo:getDir() or nil
        if not modDir then
            -- Fallback: construct mods directory path
            local docFolder = getMyDocumentFolder()
            if docFolder then
                local sep = getFileSeparator()
                modDir = docFolder .. sep .. "mods" .. sep .. "KRLZomBud"
            end
        end
        
        local srcDir = modDir .. string.gsub("/libs/", "/", getFileSeparator())
        print("[KRLZomBud] please copy files from " .. srcDir .. " to " .. dstDir)
        
        -- Determine the command line based on OS
        local cmdLine = ""
        if isSystemWindows() then
            cmdLine = "-agentlib:zbNative --"
        else
            cmdLine = "-javaagent:KRLZomBud.jar --"
        end
        
        -- Build the file list (zbNative.dll only for Windows)
        local fileList = " - KRLZomBud.jar <LINE>"
        if isSystemWindows() then
            fileList = fileList .. " - zbNative.dll <LINE>"
        end
        
        -- Get the message template and replace placeholders with actual paths, command line, and file list
        local message = ""
        if isSystemWindows() then
            local releasesURL = "https://github.com/Karl3UwU/KRL-ZomBud/releases"
            message = getText("UI_KRL_Install_Windows", releasesURL, srcDir, dstDir, cmdLine)
        else
            message = getText("UI_KRL_Install_Unix", srcDir, dstDir, cmdLine, fileList)
        end
        
        -- Replace screenshot placeholder with actual image path
        message = string.gsub(message, "SCREENSHOT_PLACEHOLDER", screenshotImage)
        
        -- Show modal dialog like the one in media/lua/client/OptionScreens/MainScreen.lua
        local windowWidth = 600 + (core:getOptionFontSizeReal() * 100)
        local windowHeight = 600
        local screenWidth = core:getScreenWidth()
        local screenHeight = core:getScreenHeight()
        local x = (screenWidth - windowWidth) / 2
        local y = screenHeight / 2 - 300
        
        local modal = ISModalRichText:new(x, y, windowWidth, windowHeight, message, false, nil, nil)
        modal:initialise()
        modal.backgroundColor = {r=0, g=0, b=0, a=0.9}
        modal.alwaysOnTop = true
        modal.chatText:paginate()
        modal:setY(screenHeight / 2 - (modal:getHeight() / 2))
        modal:setVisible(true)
        modal:addToUIManager()
    end
    
    showInstallationDialog()
end

-- Run the check when on main menu
Events.OnMainMenuEnter.Add(checkKRLZomBudInstallation)


-- Session mode (started by a launcher): stage markers for the launcher's stage file.
-- The loader writes premain, mods-loaded and quit itself; these two come from the standard Lua events.
local function markSessionStage(stage)
    if KRLZomBud and KRLZomBud.isSessionMode and KRLZomBud.isSessionMode() and KRLZomBud.markSessionStage then
        KRLZomBud.markSessionStage(stage)
    end
end
Events.OnMainMenuEnter.Add(function() markSessionStage("main-menu") end)
Events.OnGameStart.Add(function() markSessionStage("in-game") end)
